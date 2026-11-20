import { ApiError } from '../../services/apiError';
import { messagesApi, type Conversation } from '../../services/messagesApi';
import { deviceVault, type WorkerClient } from '../../crypto/client';
import { readLocalDevice, type LocalDevice } from '../../crypto/keyStore';
import type { ArchivedMessage, DirectoryMember, NoticeCode } from '../../crypto/engine';
import { newMessageId, type FileRef, type Payload } from '../../crypto/payload';
import { safetyNumber } from '../../crypto/safetyNumber';

const ATTEMPTS = 3;
const DAY_MS = 24 * 60 * 60 * 1000;

export type SyncOutcome = 'synced' | 'waiting' | 'removed';

export interface SyncResult {
  outcome: SyncOutcome;
  items: ArchivedMessage[];
  cursor: number;
}

const isStale = (error: unknown) => error instanceof ApiError && error.code === 'STALE_EPOCH';

export const isGone = (error: unknown) => error instanceof ApiError && error.status === 404;

export interface Messenger {
  device(): Promise<LocalDevice | null>;
  acceptWelcomes(): Promise<number[]>;
  sync(conversation: Conversation): Promise<SyncResult>;
  send(conversation: Conversation, payload: Payload): Promise<ArchivedMessage[]>;
  rename(conversation: Conversation, name: string): Promise<void>;
  checkTrust(conversation: Conversation): Promise<ArchivedMessage[]>;
  forgetDirectory(conversationId?: number): void;
  directory(conversationId: number): Promise<DirectoryMember[]>;
  history(conversationId: number, beforeSeq: number, limit: number): Promise<ArchivedMessage[]>;
  groupNames(conversationIds: number[]): Promise<Record<number, string | null>>;
  search(query: string, conversationIds: number[], limit: number): Promise<ArchivedMessage[]>;
  verification(conversationId: number, otherId: number): Promise<Verification | null>;
  setVerified(otherId: number, identityKey: string, verified: boolean): Promise<void>;
}

export interface Verification {
  digits: string;
  identityKey: string;
  verified: boolean;
}

export function createMessenger(vault: WorkerClient = deviceVault(), userId?: number,
  readDevice: () => Promise<LocalDevice | null> = () => readLocalDevice()): Messenger {
  const directories = new Map<number, Promise<DirectoryMember[]>>();
  const running = new Map<number, Promise<unknown>>();

  const device = async () => {
    const local = await readDevice();
    return local && (userId === undefined || local.userId === userId) ? local : null;
  };

  const requireDevice = async () => {
    const local = await device();
    if (!local) throw new Error('This browser has no messaging device');
    return local;
  };

  const directory = (conversationId: number) => {
    let found = directories.get(conversationId);
    if (!found) {
      found = messagesApi.directory(conversationId);
      found.catch(() => directories.delete(conversationId));
      directories.set(conversationId, found);
    }
    return found;
  };

  const exclusive = <T>(conversationId: number, work: () => Promise<T>): Promise<T> => {
    const previous = running.get(conversationId) ?? Promise.resolve();
    const next = previous.catch(() => undefined).then(work);
    running.set(conversationId, next);
    return next;
  };

  async function pull(conversation: Conversation, local: LocalDevice): Promise<SyncResult> {
    const items: ArchivedMessage[] = [];
    for (;;) {
      const status = await vault.groupStatus(conversation.id);
      const page = await messagesApi.messages(conversation.id, status.cursor);
      if (!page.length) return { outcome: 'synced', items, cursor: status.cursor };
      const received = await vault.receiveMessages(conversation.id, page, await directory(conversation.id),
        local.deviceId);
      items.push(...received.items);
      if (received.removed) return { outcome: 'removed', items, cursor: received.cursor };
      if (page.length < 100) return { outcome: 'synced', items, cursor: received.cursor };
    }
  }

  function sinceFor(conversation: Conversation, ownerId: number, existing: Set<number>): string | null | undefined {
    if (existing.has(ownerId)) {
      return conversation.members.find((member) => member.userId === ownerId)?.joinedAt ?? null;
    }
    if (conversation.historyVisibility === 'ALL') return null;
    if (conversation.historyVisibility === 'DAYS_30') return new Date(Date.now() - 30 * DAY_MS).toISOString();
    return undefined;
  }

  async function shareHistory(conversation: Conversation, local: LocalDevice, welcomed: number[], directoryNow: DirectoryMember[],
    rosterBefore: string[]) {
    const owners = new Map<number, number>();
    for (const member of directoryNow) {
      for (const each of member.devices) owners.set(each.id, member.userId);
    }
    const existing = new Set(rosterBefore.map((credential) => Number(/^u(\d+)\./.exec(credential)?.[1])));
    const bySince = new Map<string | null, number[]>();
    for (const deviceId of welcomed) {
      const ownerId = owners.get(deviceId);
      if (ownerId === undefined) continue;
      const since = sinceFor(conversation, ownerId, existing);
      if (since === undefined) continue;
      bySince.set(since, [...(bySince.get(since) ?? []), deviceId]);
    }
    for (const [since, deviceIds] of bySince) {
      const credentials = await vault.credentialsOf(directoryNow, deviceIds);
      for (const chunk of await vault.historyFor(conversation.id, since, credentials)) {
        await post(conversation, local, chunk);
      }
    }
  }

  async function commit(conversation: Conversation, local: LocalDevice, name?: string): Promise<boolean> {
    const status = await vault.groupStatus(conversation.id);
    const members = await directory(conversation.id);
    const wanted = status.hasGroup
      ? (await vault.planChanges(conversation.id, members)).addDeviceIds
      : await vault.liveDeviceIds(members);
    const claimed = wanted.length ? await messagesApi.claim(conversation.id, wanted) : [];
    const prepared = await vault.prepareCommit(conversation.id, members, {
      groupId: status.hasGroup ? undefined : conversation.groupId,
      keyPackages: claimed,
      name,
    });
    try {
      const posted = await messagesApi.commit(conversation.id, {
        deviceId: local.deviceId,
        epoch: prepared.epoch,
        body: prepared.commit,
        welcome: prepared.welcome,
        welcomeDeviceIds: prepared.welcomeDeviceIds,
      });
      await vault.confirmCommit(conversation.id, posted.seq, posted.createdAt);
      if (status.hasGroup && prepared.welcomeDeviceIds.length) {
        await pull(conversation, local);
        await shareHistory(conversation, local, prepared.welcomeDeviceIds, members, status.roster);
      }
      return true;
    } catch (error) {
      await vault.discardCommit(conversation.id);
      if (isStale(error)) return false;
      throw error;
    }
  }

  async function needsChanges(conversation: Conversation): Promise<boolean> {
    if (conversation.needsCommit) return true;
    const plan = await vault.planChanges(conversation.id, await directory(conversation.id));
    return plan.addDeviceIds.length > 0 || plan.removals > 0;
  }

  async function post(conversation: Conversation, local: LocalDevice, payload: Payload): Promise<ArchivedMessage[]> {
    const encrypted = await vault.encryptPayload(conversation.id, payload);
    const posted = await messagesApi.post(conversation.id, { deviceId: local.deviceId, ...encrypted });
    return vault.recordSent(conversation.id, posted.seq, payload, local.userId, posted.createdAt);
  }

  async function syncNow(conversation: Conversation): Promise<SyncResult> {
    const local = await requireDevice();
    let status = await vault.groupStatus(conversation.id);
    if (!status.hasGroup) {
      await acceptWelcomes();
      status = await vault.groupStatus(conversation.id);
    }
    if (!status.hasGroup) {
      if (conversation.epoch !== 0) return { outcome: 'waiting', items: [], cursor: 0 };
      await commit(conversation, local);
      status = await vault.groupStatus(conversation.id);
      if (!status.hasGroup) return { outcome: 'waiting', items: [], cursor: 0 };
    }
    return pull(conversation, local);
  }

  async function acceptWelcomes(): Promise<number[]> {
    const local = await device();
    if (!local) return [];
    const joined: number[] = [];
    for (const welcome of await messagesApi.welcomes(local.deviceId)) {
      try {
        const members = await messagesApi.directory(welcome.conversationId);
        directories.set(welcome.conversationId, Promise.resolve(members));
        await vault.acceptWelcome(welcome.conversationId, welcome.seq, welcome.body, members);
        joined.push(welcome.conversationId);
      } catch (error) {
        console.warn('[Messaging] Could not join from a welcome:', (error as Error).message);
      }
      await messagesApi.dropWelcome(local.deviceId, welcome.id);
    }
    return joined;
  }

  async function withRetries<T>(conversation: Conversation, attempt: (current: Conversation) => Promise<T | null>): Promise<T> {
    let current = conversation;
    for (let tried = 1; ; tried++) {
      const result = await attempt(current);
      if (result !== null) return result;
      if (tried >= ATTEMPTS) throw new ApiError('The conversation kept changing', { status: 409, code: 'STALE_EPOCH' });
      directories.delete(current.id);
      current = await messagesApi.get(current.id);
      await syncNow(current);
    }
  }

  const noticeCodes = (codes: NoticeCode[], userIdOf: number) => codes.map((code) => ({ code, userId: userIdOf }));

  return {
    device,

    acceptWelcomes,

    sync: (conversation) => exclusive(conversation.id, () => syncNow(conversation)),

    send: (conversation, payload) => exclusive(conversation.id, async () => {
      const local = await requireDevice();
      await syncNow(conversation);
      return withRetries(conversation, async (current) => {
        if (await needsChanges(current) && !await commit(current, local)) return null;
        try {
          return await post(current, local, payload);
        } catch (error) {
          if (isStale(error)) return null;
          throw error;
        }
      });
    }),

    rename: (conversation, name) => exclusive(conversation.id, async () => {
      const local = await requireDevice();
      if ((await vault.groupStatus(conversation.id)).hasGroup || conversation.epoch !== 0) {
        await syncNow(conversation);
      }
      await withRetries(conversation, async (current) => ((await commit(current, local, name)) ? true : null));
    }),

    async checkTrust(conversation) {
      const local = await device();
      if (!local) return [];
      const members = await directory(conversation.id);
      const others = members.filter((member) => member.userId !== local.userId);
      const pins = await vault.pins(others.map((member) => member.userId));
      const contacts = await Promise.all(others.map(async (member) => {
        const pin = pins.find((each) => each.userId === member.userId);
        return {
          userId: member.userId,
          identityKey: member.identityKey,
          log: await messagesApi.keyLog(member.userId, pin?.logId ?? 0),
        };
      }));
      const results = await vault.checkContacts(contacts);
      const codes = results.flatMap((result) => noticeCodes(result.notices, result.userId));
      return codes.length ? vault.addNotices(conversation.id, codes, new Date().toISOString()) : [];
    },

    forgetDirectory(conversationId) {
      if (conversationId === undefined) directories.clear(); else directories.delete(conversationId);
    },

    directory,

    history: (conversationId, beforeSeq, limit) => vault.readArchive(conversationId, beforeSeq, limit),

    search: (query, conversationIds, limit) => vault.search(query, conversationIds, limit),

    async groupNames(conversationIds) {
      if (!await device()) return {};
      const statuses = await Promise.all(conversationIds.map((id) => vault.groupStatus(id)));
      return Object.fromEntries(conversationIds.map((id, index) => [id, statuses[index].name]));
    },

    async verification(conversationId, otherId) {
      const local = await device();
      if (!local) return null;
      const members = await directory(conversationId);
      const mine = members.find((member) => member.userId === local.userId)?.identityKey;
      const theirs = members.find((member) => member.userId === otherId)?.identityKey;
      if (!mine || !theirs) return null;
      const [pin] = await vault.pins([otherId]);
      return {
        digits: safetyNumber({ userId: local.userId, identityKey: mine }, { userId: otherId, identityKey: theirs }),
        identityKey: theirs,
        verified: pin.verified && pin.identityKey === theirs,
      };
    },

    setVerified: (otherId, identityKey, verified) => vault.setVerified(otherId, identityKey, verified),
  };
}

export interface TextExtras {
  thread?: string | null;
  mentions?: number[];
  ttl?: number | null;
  files?: FileRef[];
}

export const textPayload = (text: string, replyTo: string | null, extras: TextExtras = {}): Payload => ({
  t: 'text', id: newMessageId(), text,
  ...(replyTo ? { replyTo } : {}),
  ...(extras.thread ? { thread: extras.thread } : {}),
  ...(extras.mentions?.length ? { mentions: extras.mentions } : {}),
  ...(extras.ttl ? { ttl: extras.ttl } : {}),
  ...(extras.files?.length ? { files: extras.files } : {}),
});
