import { decodeMlsMessage, type ClientState } from 'ts-mls';
import type { Archive, ArchiveRecord } from './archive';
import { buffer, fromBase64, toBase64, utf8 } from './bytes';
import { openKeyPackage, openStoredDevice, keyPackageKey, type OpenedDevice } from './deviceVault';
import {
  commitChanges,
  decodeKeyPackage,
  decodeState,
  encodeState,
  encrypt,
  foundGroup,
  groupEpoch,
  groupName,
  joinFromWelcome,
  receive,
  roster,
  type CredentialCheck,
  type GroupSigner,
} from './groups';
import { chainHolds, type KeyLogEntry } from './keyLog';
import { ciphersuite, deviceSignaturePayload, open, seal } from './keys';
import type { KeyStore } from './keyStore';
import { decodeEnvelope, encodeEnvelope, signedBytes, type FileRef, type HistoryItem, type Payload } from './payload';

const HISTORY_LIMIT = 1000;
const HISTORY_CHUNK = 150;
const CREDENTIAL = /^u(\d+)\.[0-9a-f]{32}$/;

export interface DirectoryDevice {
  id: number;
  signatureKey: string;
  credential: string;
  identitySignature: string;
  revoked: boolean;
}

export interface DirectoryMember {
  userId: number;
  identityKey: string | null;
  devices: DirectoryDevice[];
}

export interface ServerMessage {
  seq: number;
  epoch: number;
  kind: 'COMMIT' | 'APPLICATION';
  senderId: number | null;
  senderDeviceId: number | null;
  body: string;
  createdAt: string;
}

export type NoticeCode = 'undecryptable' | 'joined' | 'left' | 'identity-changed' | 'device-added' | 'log-rewritten';

export interface ArchivedMessage {
  conversationId: number;
  seq: number;
  id: string;
  senderId: number | null;
  sentAt: string;
  kind: 'text' | 'notice';
  text: string;
  replyTo: string | null;
  editedAt: string | null;
  deleted: boolean;
  reactions: Record<string, number[]>;
  notice: { code: NoticeCode; userId: number | null } | null;
  threadId?: string | null;
  mentions?: number[];
  expiresAt?: string | null;
  files?: FileRef[];
}

export interface GroupStatus {
  hasGroup: boolean;
  epoch: number;
  cursor: number;
  name: string | null;
  roster: string[];
}

export interface PlannedChanges {
  addDeviceIds: number[];
  removals: number;
}

export interface CommitRequestBody {
  groupId?: string;
  keyPackages: { deviceId: number; keyPackage: string }[];
  name?: string | null;
}

export interface PreparedCommitBody {
  epoch: number;
  commit: string;
  welcome: string | null;
  welcomeDeviceIds: number[];
}

export interface ReceiveResult {
  items: ArchivedMessage[];
  cursor: number;
  epoch: number;
  removed: boolean;
}

export interface ContactKeys {
  userId: number;
  identityKey: string | null;
  log: KeyLogEntry[];
}

export interface Pin {
  userId: number;
  identityKey: string | null;
  logId: number;
  logHash: string | null;
  verified: boolean;
}

export interface ContactNotices {
  userId: number;
  notices: NoticeCode[];
}

export class NoGroupError extends Error {
  constructor() {
    super('NoGroup');
    this.name = 'NoGroupError';
  }
}

export class NoKeyPackageError extends Error {
  constructor() {
    super('NoKeyPackage');
    this.name = 'NoKeyPackageError';
  }
}

interface StoredGroup {
  sealed: Uint8Array;
  cursor: number;
  groupId: Uint8Array;
}

interface PendingCommit {
  epoch: number;
  commit: Uint8Array;
  sealed: Uint8Array;
  groupId: Uint8Array;
}

interface TrustedDevice {
  userId: number;
  deviceId: number;
  credential: string;
  signatureKey: Uint8Array;
  live: boolean;
}

interface Trusted {
  byCredential: Map<string, TrustedDevice>;
  members: Set<number>;
}

export type Lock = <T>(work: () => Promise<T>) => Promise<T>;

let queue: Promise<unknown> = Promise.resolve();

export const browserLock: Lock = <T>(work: () => Promise<T>): Promise<T> => {
  const locks = (globalThis.navigator as (Navigator & { locks?: LockManager }) | undefined)?.locks;
  if (locks) {
    return locks.request('kanban-messaging', work) as Promise<T>;
  }
  const run = queue.then(work, work);
  queue = run.catch(() => undefined);
  return run;
};

const decoder = new TextDecoder();
const text = (bytes: Uint8Array) => decoder.decode(bytes);
const userOf = (credential: string): number | null => {
  const match = CREDENTIAL.exec(credential);
  return match ? Number(match[1]) : null;
};
const same = (one: Uint8Array, other: Uint8Array) =>
  one.length === other.length && one.every((byte, index) => byte === other[index]);
const groupKey = (conversationId: number) => `group:${conversationId}`;
const pendingKey = (conversationId: number) => `pending:${conversationId}`;
const pinKey = (userId: number) => `pin:${userId}`;
const label = (...parts: (string | number)[]) => utf8(parts.join(':'));
const isExpired = (expiresAt: string | null | undefined, now: number) => Boolean(expiresAt) && Date.parse(expiresAt as string) <= now;
const fold = (value: string) => value.normalize('NFD').replace(/\p{Diacritic}/gu, '').toLowerCase();

async function signatureHolds(identityKey: Uint8Array, signature: Uint8Array, payload: Uint8Array): Promise<boolean> {
  try {
    const key = await crypto.subtle.importKey('raw', buffer(identityKey), 'Ed25519', false, ['verify']);
    return await crypto.subtle.verify('Ed25519', key, buffer(signature), buffer(payload));
  } catch {
    return false;
  }
}

export function createMessagingEngine(store: KeyStore, archive: Archive, lock: Lock = browserLock) {
  const verifiedDevices = new Map<string, boolean>();

  const signerOf = (opened: OpenedDevice): GroupSigner => opened.secrets;
  const ownCredential = (opened: OpenedDevice) => text(opened.secrets.credential);

  async function trust(directory: DirectoryMember[]): Promise<Trusted> {
    const byCredential = new Map<string, TrustedDevice>();
    const members = new Set<number>();
    for (const member of directory) {
      members.add(member.userId);
      if (!member.identityKey) continue;
      const identityKey = fromBase64(member.identityKey);
      for (const device of member.devices) {
        const signatureKey = fromBase64(device.signatureKey);
        const credentialBytes = fromBase64(device.credential);
        const credential = text(credentialBytes);
        if (userOf(credential) !== member.userId) continue;
        const cacheKey = [member.identityKey, device.signatureKey, device.credential, device.identitySignature].join('|');
        let holds = verifiedDevices.get(cacheKey);
        if (holds === undefined) {
          holds = await signatureHolds(identityKey, fromBase64(device.identitySignature),
            deviceSignaturePayload(signatureKey, credentialBytes));
          verifiedDevices.set(cacheKey, holds);
        }
        if (holds) {
          byCredential.set(credential, { userId: member.userId, deviceId: device.id, credential, signatureKey,
            live: !device.revoked });
        }
      }
    }
    return { byCredential, members };
  }

  const checkFor = (trusted: Trusted): CredentialCheck => (credentialBytes, signatureKey) => {
    const credential = text(credentialBytes);
    const userId = userOf(credential);
    if (userId === null) return false;
    if (!trusted.members.has(userId)) return true;
    const device = trusted.byCredential.get(credential);
    return Boolean(device && same(device.signatureKey, signatureKey));
  };

  const permissive: CredentialCheck = () => true;

  async function loadGroup(opened: OpenedDevice, conversationId: number, check: CredentialCheck):
    Promise<{ state: ClientState; cursor: number; groupId: Uint8Array } | null> {
    const stored = await store.get<StoredGroup | null>(groupKey(conversationId));
    if (!stored) return null;
    const bytes = await open(opened.stored.storageKey, stored.sealed, label('group', conversationId));
    return { state: decodeState(bytes, check), cursor: stored.cursor, groupId: stored.groupId };
  }

  async function saveGroup(opened: OpenedDevice, conversationId: number, state: ClientState, cursor: number) {
    await store.put(groupKey(conversationId), {
      sealed: await seal(opened.stored.storageKey, encodeState(state), label('group', conversationId)),
      cursor,
      groupId: state.groupContext.groupId,
    } satisfies StoredGroup);
  }

  async function sealRecord(opened: OpenedDevice, message: ArchivedMessage): Promise<ArchiveRecord> {
    return {
      conversationId: message.conversationId,
      seq: message.seq,
      id: message.id,
      sealed: await seal(opened.stored.storageKey, utf8(JSON.stringify(message)),
        label('archive', message.conversationId, message.id)),
    };
  }

  async function openRecord(opened: OpenedDevice, record: ArchiveRecord): Promise<ArchivedMessage> {
    const plain = await open(opened.stored.storageKey, record.sealed, label('archive', record.conversationId, record.id));
    return JSON.parse(text(plain));
  }

  async function keep(opened: OpenedDevice, message: ArchivedMessage) {
    await archive.put(await sealRecord(opened, message));
  }

  async function liveOnly(messages: ArchivedMessage[]): Promise<ArchivedMessage[]> {
    const now = Date.now();
    const expired = messages.filter((message) => isExpired(message.expiresAt, now));
    for (const message of expired) await archive.remove(message.conversationId, message.seq);
    return expired.length ? messages.filter((message) => !isExpired(message.expiresAt, now)) : messages;
  }

  async function byId(opened: OpenedDevice, conversationId: number, id: string): Promise<ArchivedMessage | null> {
    const record = await archive.byId(conversationId, id);
    return record ? openRecord(opened, record) : null;
  }

  async function notice(opened: OpenedDevice, conversationId: number, afterSeq: number, code: NoticeCode,
    userId: number | null, sentAt: string): Promise<ArchivedMessage> {
    const nearby = await archive.before(conversationId, afterSeq + 1, 1000);
    const taken = nearby.filter((record) => record.seq > afterSeq).length;
    const message: ArchivedMessage = {
      conversationId, seq: afterSeq + (taken + 1) / 1000, id: `notice-${afterSeq}-${taken}-${code}-${userId ?? ''}`,
      senderId: null, sentAt, kind: 'notice', text: '', replyTo: null, editedAt: null, deleted: false, reactions: {},
      notice: { code, userId },
    };
    await keep(opened, message);
    return message;
  }

  const usersIn = (state: ClientState) =>
    new Set(roster(state).map((leaf) => userOf(text(leaf.credential))).filter((id): id is number => id !== null));

  async function rosterNotices(opened: OpenedDevice, conversationId: number, before: ClientState, after: ClientState,
    seq: number, sentAt: string): Promise<ArchivedMessage[]> {
    const was = usersIn(before);
    const is = usersIn(after);
    const notices: ArchivedMessage[] = [];
    for (const userId of is) {
      if (!was.has(userId)) notices.push(await notice(opened, conversationId, seq, 'joined', userId, sentAt));
    }
    for (const userId of was) {
      if (!is.has(userId)) notices.push(await notice(opened, conversationId, seq, 'left', userId, sentAt));
    }
    return notices;
  }

  async function apply(opened: OpenedDevice, conversationId: number, seq: number, payload: Payload,
    senderId: number | null, sentAt: string): Promise<ArchivedMessage[]> {
    if (payload.t === 'text') {
      const expiresAt = payload.ttl ? new Date(Date.parse(sentAt) + payload.ttl * 1000).toISOString() : null;
      if (isExpired(expiresAt, Date.now())) return [];
      const message: ArchivedMessage = {
        conversationId, seq, id: payload.id, senderId, sentAt, kind: 'text', text: payload.text,
        replyTo: payload.replyTo ?? null, editedAt: null, deleted: false, reactions: {}, notice: null,
        threadId: payload.thread ?? null, mentions: payload.mentions ?? [], expiresAt, files: payload.files ?? [],
      };
      await keep(opened, message);
      return [message];
    }
    if (payload.t === 'history') {
      if (!payload.for.includes(ownCredential(opened))) return [];
      const imported: ArchivedMessage[] = [];
      for (const item of payload.items) {
        if (isExpired(item.expiresAt, Date.now()) || await byId(opened, conversationId, item.id)) continue;
        const message: ArchivedMessage = {
          conversationId, seq: item.seq, id: item.id, senderId: item.senderId, sentAt: item.sentAt, kind: 'text',
          text: item.deleted ? '' : item.text, replyTo: item.replyTo, editedAt: item.editedAt, deleted: item.deleted,
          reactions: item.reactions ?? {}, notice: null, threadId: item.threadId ?? null, mentions: item.mentions ?? [],
          expiresAt: item.expiresAt ?? null, files: item.deleted ? [] : item.files ?? [],
        };
        await keep(opened, message);
        imported.push(message);
      }
      return imported;
    }
    const target = await byId(opened, conversationId, payload.target);
    if (!target || target.kind !== 'text' || target.deleted) return [];
    if (payload.t === 'react') {
      if (senderId === null) return [];
      const reacting = new Set(target.reactions[payload.emoji] ?? []);
      if (payload.on) reacting.add(senderId); else reacting.delete(senderId);
      if (reacting.size) target.reactions[payload.emoji] = [...reacting]; else delete target.reactions[payload.emoji];
    } else {
      if (target.senderId !== senderId) return [];
      if (payload.t === 'edit') {
        target.text = payload.text;
        target.editedAt = sentAt;
      } else {
        target.deleted = true;
        target.text = '';
        target.reactions = {};
        target.files = [];
      }
    }
    await keep(opened, target);
    return [target];
  }

  async function openPending(opened: OpenedDevice, conversationId: number, check: CredentialCheck):
    Promise<{ pending: PendingCommit; state: ClientState } | null> {
    const pending = await store.get<PendingCommit | null>(pendingKey(conversationId));
    if (!pending) return null;
    const bytes = await open(opened.stored.storageKey, pending.sealed, label('pending', conversationId));
    return { pending, state: decodeState(bytes, check) };
  }

  return {
    async groupStatus(conversationId: number): Promise<GroupStatus> {
      return lock(async () => {
        const opened = await openStoredDevice(store);
        const group = await loadGroup(opened, conversationId, permissive);
        if (!group) return { hasGroup: false, epoch: -1, cursor: 0, name: null, roster: [] };
        return {
          hasGroup: true,
          epoch: groupEpoch(group.state),
          cursor: group.cursor,
          name: groupName(group.state),
          roster: roster(group.state).map((leaf) => text(leaf.credential)),
        };
      });
    },

    async planChanges(conversationId: number, directory: DirectoryMember[]): Promise<PlannedChanges> {
      return lock(async () => {
        const opened = await openStoredDevice(store);
        const trusted = await trust(directory);
        const group = await loadGroup(opened, conversationId, checkFor(trusted));
        if (!group) throw new NoGroupError();
        const own = ownCredential(opened);
        const present = new Set(roster(group.state).map((leaf) => text(leaf.credential)));
        const wanted = [...trusted.byCredential.values()].filter((device) => device.live);
        const wantedCredentials = new Set(wanted.map((device) => device.credential));
        return {
          addDeviceIds: wanted.filter((device) => device.credential !== own && !present.has(device.credential))
            .map((device) => device.deviceId),
          removals: [...present].filter((credential) => credential !== own && !wantedCredentials.has(credential)).length,
        };
      });
    },

    async liveDeviceIds(directory: DirectoryMember[]): Promise<number[]> {
      return lock(async () => {
        const opened = await openStoredDevice(store);
        const own = ownCredential(opened);
        return [...(await trust(directory)).byCredential.values()]
          .filter((device) => device.live && device.credential !== own)
          .map((device) => device.deviceId);
      });
    },

    async prepareCommit(conversationId: number, directory: DirectoryMember[], request: CommitRequestBody):
      Promise<PreparedCommitBody> {
      return lock(async () => {
        const opened = await openStoredDevice(store);
        const trusted = await trust(directory);
        const check = checkFor(trusted);
        const group = await loadGroup(opened, conversationId, check);
        let state: ClientState;
        if (group) {
          state = group.state;
        } else if (request.groupId) {
          state = await foundGroup(fromBase64(request.groupId), signerOf(opened), request.name ?? null, check);
        } else {
          throw new NoGroupError();
        }
        const own = ownCredential(opened);
        const present = new Set(roster(state).map((leaf) => text(leaf.credential)));
        const adds = [];
        const welcomeDeviceIds: number[] = [];
        for (const claimed of request.keyPackages) {
          const keyPackage = decodeKeyPackage(fromBase64(claimed.keyPackage));
          const credential = keyPackage.leafNode.credential.credentialType === 'basic'
            ? text(keyPackage.leafNode.credential.identity) : '';
          const device = trusted.byCredential.get(credential);
          if (!device || !device.live || device.deviceId !== claimed.deviceId || present.has(credential)
            || !same(device.signatureKey, keyPackage.leafNode.signaturePublicKey)) {
            continue;
          }
          present.add(credential);
          adds.push(keyPackage);
          welcomeDeviceIds.push(claimed.deviceId);
        }
        const removals = roster(state)
          .filter((leaf) => {
            const credential = text(leaf.credential);
            return credential !== own && !trusted.byCredential.get(credential)?.live;
          })
          .map((leaf) => leaf.leafIndex);
        const rename = group && request.name != null && request.name !== groupName(state) ? request.name : undefined;
        const epoch = groupEpoch(state);
        const prepared = await commitChanges(state, { add: adds, remove: removals, name: rename });
        await store.put(pendingKey(conversationId), {
          epoch,
          commit: prepared.commit,
          sealed: await seal(opened.stored.storageKey, encodeState(prepared.state), label('pending', conversationId)),
          groupId: state.groupContext.groupId,
        } satisfies PendingCommit);
        return {
          epoch,
          commit: toBase64(prepared.commit),
          welcome: prepared.welcome ? toBase64(prepared.welcome) : null,
          welcomeDeviceIds: prepared.welcome ? welcomeDeviceIds : [],
        };
      });
    },

    async confirmCommit(conversationId: number, seq: number, sentAt: string): Promise<ArchivedMessage[]> {
      return lock(async () => {
        const opened = await openStoredDevice(store);
        const adopted = await openPending(opened, conversationId, permissive);
        if (!adopted) return [];
        const group = await loadGroup(opened, conversationId, permissive);
        if (group && group.cursor !== seq - 1) return [];
        await saveGroup(opened, conversationId, adopted.state, seq);
        await store.put(pendingKey(conversationId), null);
        return group ? rosterNotices(opened, conversationId, group.state, adopted.state, seq, sentAt) : [];
      });
    },

    async discardCommit(conversationId: number): Promise<void> {
      await lock(() => store.put(pendingKey(conversationId), null));
    },

    async acceptWelcome(conversationId: number, seq: number, body: string, directory: DirectoryMember[]): Promise<void> {
      await lock(async () => {
        const opened = await openStoredDevice(store);
        const bytes = fromBase64(body);
        const decoded = decodeMlsMessage(bytes, 0)?.[0];
        if (decoded?.wireformat !== 'mls_welcome') throw new NoKeyPackageError();
        for (const secret of decoded.welcome.secrets) {
          const keyPackage = await openKeyPackage(store, opened.stored.storageKey, secret.newMember);
          if (!keyPackage) continue;
          const state = await joinFromWelcome(bytes, decodeKeyPackage(keyPackage.data), {
            initPrivateKey: keyPackage.initPrivateKey,
            hpkePrivateKey: keyPackage.hpkePrivateKey,
            signaturePrivateKey: opened.secrets.signKey,
          }, checkFor(await trust(directory)));
          await saveGroup(opened, conversationId, state, seq);
          await store.put(pendingKey(conversationId), null);
          if (!keyPackage.lastResort) await store.put(keyPackageKey(secret.newMember), null);
          return;
        }
        throw new NoKeyPackageError();
      });
    },

    async receiveMessages(conversationId: number, messages: ServerMessage[], directory: DirectoryMember[],
      ownDeviceId: number): Promise<ReceiveResult> {
      return lock(async () => {
        const opened = await openStoredDevice(store);
        const trusted = await trust(directory);
        const check = checkFor(trusted);
        const group = await loadGroup(opened, conversationId, check);
        if (!group) throw new NoGroupError();
        const impl = await ciphersuite();
        const own = ownCredential(opened);
        let { state, cursor } = group;
        const items: ArchivedMessage[] = [];
        let removed = false;
        for (const message of [...messages].sort((a, b) => a.seq - b.seq)) {
          if (message.seq <= cursor) continue;
          const body = fromBase64(message.body);
          if (message.kind === 'COMMIT') {
            if (message.epoch !== groupEpoch(state)) {
              cursor = message.seq;
              continue;
            }
            let next: ClientState;
            const adopted = await openPending(opened, conversationId, check);
            if (adopted && adopted.pending.epoch === message.epoch && same(adopted.pending.commit, body)) {
              next = adopted.state;
            } else {
              try {
                next = (await receive(state, body)).state;
              } catch {
                items.push(await notice(opened, conversationId, message.seq, 'undecryptable', message.senderId,
                  message.createdAt));
                cursor = message.seq;
                continue;
              }
            }
            if (adopted && adopted.pending.epoch <= message.epoch) await store.put(pendingKey(conversationId), null);
            items.push(...await rosterNotices(opened, conversationId, state, next, message.seq, message.createdAt));
            state = next;
            cursor = message.seq;
            if (!roster(state).some((leaf) => text(leaf.credential) === own)) {
              removed = true;
              break;
            }
            continue;
          }
          cursor = message.seq;
          if (message.senderDeviceId === ownDeviceId) continue;
          try {
            const received = await receive(state, body);
            state = received.state;
            if (received.kind !== 'application') continue;
            const envelope = decodeEnvelope(received.plaintext);
            const sender = trusted.byCredential.get(envelope.credential);
            if (!sender || !await impl.signature.verify(sender.signatureKey,
              signedBytes(state.groupContext.groupId, envelope.payload), envelope.signature)) {
              throw new Error('Unsigned message');
            }
            items.push(...await apply(opened, conversationId, message.seq, envelope.payload, sender.userId,
              message.createdAt));
          } catch {
            items.push(await notice(opened, conversationId, message.seq, 'undecryptable', message.senderId,
              message.createdAt));
          }
        }
        if (removed) {
          await store.put(groupKey(conversationId), null);
        } else {
          await saveGroup(opened, conversationId, state, cursor);
        }
        return { items, cursor, epoch: removed ? -1 : groupEpoch(state), removed };
      });
    },

    async encryptPayload(conversationId: number, payload: Payload): Promise<{ epoch: number; body: string }> {
      return lock(async () => {
        const opened = await openStoredDevice(store);
        const group = await loadGroup(opened, conversationId, permissive);
        if (!group) throw new NoGroupError();
        const impl = await ciphersuite();
        const signature = await impl.signature.sign(opened.secrets.signKey,
          signedBytes(group.state.groupContext.groupId, payload));
        const encrypted = await encrypt(group.state,
          encodeEnvelope({ payload, credential: ownCredential(opened), signature }));
        await saveGroup(opened, conversationId, encrypted.state, group.cursor);
        return { epoch: groupEpoch(group.state), body: toBase64(encrypted.body) };
      });
    },

    async recordSent(conversationId: number, seq: number, payload: Payload, senderId: number, sentAt: string):
      Promise<ArchivedMessage[]> {
      return lock(async () => {
        const stored = await store.get<StoredGroup | null>(groupKey(conversationId));
        if (stored && stored.cursor === seq - 1) {
          await store.put(groupKey(conversationId), { ...stored, cursor: seq });
        }
        return apply(await openStoredDevice(store), conversationId, seq, payload, senderId, sentAt);
      });
    },

    async readArchive(conversationId: number, beforeSeq: number, limit: number): Promise<ArchivedMessage[]> {
      return lock(async () => {
        const opened = await openStoredDevice(store);
        const records = await archive.before(conversationId, beforeSeq, limit);
        return liveOnly(await Promise.all(records.map((record) => openRecord(opened, record))));
      });
    },

    async search(query: string, conversationIds: number[], limit: number): Promise<ArchivedMessage[]> {
      return lock(async () => {
        const needle = fold(query.trim());
        if (!needle) return [];
        const opened = await openStoredDevice(store);
        const found: ArchivedMessage[] = [];
        for (const conversationId of conversationIds) {
          const records = await archive.all(conversationId);
          const messages = await liveOnly(await Promise.all(records.map((record) => openRecord(opened, record))));
          for (const message of messages) {
            if (message.kind !== 'text' || message.deleted) continue;
            const haystack = [message.text, ...(message.files ?? []).map((file) => file.name)].join('\n');
            if (fold(haystack).includes(needle)) found.push(message);
          }
        }
        return found.sort((a, b) => Date.parse(b.sentAt) - Date.parse(a.sentAt)).slice(0, limit);
      });
    },

    async historyFor(conversationId: number, since: string | null, credentials: string[]): Promise<Payload[]> {
      return lock(async () => {
        const opened = await openStoredDevice(store);
        const records = await archive.all(conversationId);
        const items: HistoryItem[] = [];
        const live = await liveOnly(await Promise.all(records.slice(-HISTORY_LIMIT)
          .map((record) => openRecord(opened, record))));
        for (const message of live) {
          if (message.kind !== 'text' || (since && message.sentAt < since)) continue;
          items.push({
            seq: message.seq, id: message.id, senderId: message.senderId, sentAt: message.sentAt, text: message.text,
            replyTo: message.replyTo, editedAt: message.editedAt, deleted: message.deleted, reactions: message.reactions,
            threadId: message.threadId ?? null, mentions: message.mentions ?? [], expiresAt: message.expiresAt ?? null,
            files: message.files ?? [],
          });
        }
        const chunks: Payload[] = [];
        for (let start = 0; start < items.length; start += HISTORY_CHUNK) {
          chunks.push({ t: 'history', id: crypto.randomUUID(), for: credentials,
            items: items.slice(start, start + HISTORY_CHUNK) });
        }
        return chunks;
      });
    },

    async credentialsOf(directory: DirectoryMember[], deviceIds: number[]): Promise<string[]> {
      const wanted = new Set(deviceIds);
      return [...(await trust(directory)).byCredential.values()]
        .filter((device) => wanted.has(device.deviceId))
        .map((device) => device.credential);
    },

    async addNotices(conversationId: number, codes: { code: NoticeCode; userId: number | null }[], sentAt: string):
      Promise<ArchivedMessage[]> {
      return lock(async () => {
        const opened = await openStoredDevice(store);
        const stored = await store.get<StoredGroup | null>(groupKey(conversationId));
        const added: ArchivedMessage[] = [];
        for (const { code, userId } of codes) {
          added.push(await notice(opened, conversationId, stored?.cursor ?? 0, code, userId, sentAt));
        }
        return added;
      });
    },

    async dropConversation(conversationId: number): Promise<void> {
      await lock(async () => {
        await archive.drop(conversationId);
        await store.put(groupKey(conversationId), null);
        await store.put(pendingKey(conversationId), null);
      });
    },

    async pins(userIds: number[]): Promise<Pin[]> {
      return lock(async () => Promise.all(userIds.map(async (userId) =>
        (await store.get<Pin | null>(pinKey(userId)))
          ?? { userId, identityKey: null, logId: 0, logHash: null, verified: false })));
    },

    async checkContacts(contacts: ContactKeys[]): Promise<ContactNotices[]> {
      return lock(async () => {
        const results: ContactNotices[] = [];
        for (const contact of contacts) {
          const pin = await store.get<Pin | null>(pinKey(contact.userId));
          const notices: NoticeCode[] = [];
          if (pin && !await chainHolds(contact.log, pin.logHash)) {
            notices.push('log-rewritten');
          }
          if (pin) {
            if (contact.log.some((entry) => entry.change === 'IDENTITY')
              || (pin.identityKey !== null && contact.identityKey !== pin.identityKey)) {
              notices.push('identity-changed');
            }
            if (contact.log.some((entry) => entry.change === 'DEVICE_ADDED')) {
              notices.push('device-added');
            }
          }
          const last = contact.log[contact.log.length - 1];
          const identityChanged = notices.includes('identity-changed');
          await store.put(pinKey(contact.userId), {
            userId: contact.userId,
            identityKey: contact.identityKey,
            logId: last?.id ?? pin?.logId ?? 0,
            logHash: last?.entryHash ?? pin?.logHash ?? null,
            verified: Boolean(pin?.verified) && !identityChanged,
          } satisfies Pin);
          results.push({ userId: contact.userId, notices: [...new Set(notices)] });
        }
        return results;
      });
    },

    async setVerified(userId: number, identityKey: string, verified: boolean): Promise<void> {
      await lock(async () => {
        const pin = await store.get<Pin | null>(pinKey(userId));
        await store.put(pinKey(userId), {
          userId,
          identityKey,
          logId: pin?.logId ?? 0,
          logHash: pin?.logHash ?? null,
          verified: verified && (pin?.identityKey ?? identityKey) === identityKey,
        } satisfies Pin);
      });
    },

    async wipeMessages(): Promise<void> {
      await lock(() => archive.clear());
    },
  };
}

export type MessagingEngine = ReturnType<typeof createMessagingEngine>;
