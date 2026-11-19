/**
 * @jest-environment node
 */
import { memoryArchive } from '../../crypto/archive';
import { toBase64 } from '../../crypto/bytes';
import { createDeviceVault } from '../../crypto/deviceVault';
import { createMessagingEngine, type DirectoryMember, type Lock, type ServerMessage } from '../../crypto/engine';
import { memoryKeyStore, type LocalDevice } from '../../crypto/keyStore';
import type { WorkerClient } from '../../crypto/client';
import type { KeyLogEntry } from '../../crypto/keyLog';
import { ApiError } from '../../services/apiError';
import type { Conversation, HistoryVisibility } from '../../services/messagesApi';
import { createMessenger, isGone, textPayload, type Messenger } from '../../context/messages/messenger';

const serial: Lock = (work) => work();

interface FakeDevice {
  userId: number;
  deviceId: number;
  messenger: Messenger;
  entry: DirectoryMember['devices'][number];
  identityKey: string;
  packages: string[];
}

class FakeServer {
  epoch = 0;
  needsCommit = false;
  messages: ServerMessage[] = [];
  welcomes: { id: number; deviceId: number; conversationId: number; seq: number; body: string }[] = [];
  devices: FakeDevice[] = [];
  memberIds: number[] = [];
  joinedAt: Record<number, string> = {};
  historyVisibility: HistoryVisibility = 'JOINED';
  keyLogs: Record<number, KeyLogEntry[]> = {};
  refuseNextPost = false;
  nextWelcome = 1;

  conversation(): Conversation {
    return {
      id: 7, kind: 'GROUP', groupId: toBase64(new TextEncoder().encode('group-7')), epoch: this.epoch,
      needsCommit: this.needsCommit, historyVisibility: this.historyVisibility, whoCanAdd: 'MEMBERS',
      lastSeq: this.messages.length, createdAt: '2026-11-19T10:00:00Z', lastActivityAt: '2026-11-19T10:00:00Z',
      unread: 0, me: { role: 'ADMIN', state: 'ACTIVE', joinedSeq: 1, readSeq: 0, muted: false, pinned: false, archived: false },
      members: this.memberIds.map((userId) => ({ userId, name: `user ${userId}`, hasAvatar: false, role: 'MEMBER',
        state: 'ACTIVE', joinedAt: this.joinedAt[userId] ?? '2026-11-19T10:00:00Z' })),
    };
  }

  directory(): DirectoryMember[] {
    return this.memberIds.map((userId) => {
      const owned = this.devices.filter((device) => device.userId === userId);
      return { userId, identityKey: owned[0]?.identityKey ?? null, devices: owned.map((device) => device.entry) };
    });
  }

  stale() {
    return new ApiError('stale', { status: 409, code: 'STALE_EPOCH' });
  }

  api = {
    get: async () => this.conversation(),
    directory: async () => this.directory(),
    claim: async (_id: number, deviceIds: number[]) => this.devices
      .filter((device) => deviceIds.includes(device.deviceId) && device.packages.length)
      .map((device) => ({ deviceId: device.deviceId, userId: device.userId, keyPackage: device.packages.shift()! })),
    commit: async (_id: number, body: { deviceId: number; epoch: number; body: string; welcome: string | null;
      welcomeDeviceIds: number[] }) => {
      if (body.epoch !== this.epoch) throw this.stale();
      this.epoch++;
      this.needsCommit = false;
      const seq = this.messages.length + 1;
      const sender = this.devices.find((device) => device.deviceId === body.deviceId)!;
      this.messages.push({ seq, epoch: body.epoch, kind: 'COMMIT', senderId: sender.userId, senderDeviceId: body.deviceId,
        body: body.body, createdAt: new Date(seq * 1000).toISOString() });
      body.welcomeDeviceIds.forEach((deviceId) => this.welcomes.push(
        { id: this.nextWelcome++, deviceId, conversationId: 7, seq, body: body.welcome! }));
      return { seq, epoch: this.epoch, createdAt: new Date(seq * 1000).toISOString() };
    },
    post: async (_id: number, body: { deviceId: number; epoch: number; body: string }) => {
      if (this.refuseNextPost) {
        this.refuseNextPost = false;
        throw this.stale();
      }
      if (body.epoch !== this.epoch || this.needsCommit) throw this.stale();
      const seq = this.messages.length + 1;
      const sender = this.devices.find((device) => device.deviceId === body.deviceId)!;
      this.messages.push({ seq, epoch: body.epoch, kind: 'APPLICATION', senderId: sender.userId,
        senderDeviceId: body.deviceId, body: body.body, createdAt: new Date(seq * 1000).toISOString() });
      return { seq, epoch: this.epoch, createdAt: new Date(seq * 1000).toISOString() };
    },
    messages: async (_id: number, after: number) => {
      if (!this.devices.length) throw new ApiError('gone', { status: 404 });
      return this.messages.filter((message) => message.seq > after).slice(0, 100);
    },
    welcomes: async (deviceId: number) => this.welcomes.filter((welcome) => welcome.deviceId === deviceId),
    dropWelcome: async (_deviceId: number, welcomeId: number) => {
      this.welcomes = this.welcomes.filter((welcome) => welcome.id !== welcomeId);
      return true as const;
    },
    keyLog: async (userId: number, after: number) => (this.keyLogs[userId] ?? []).filter((entry) => entry.id > after),
  };
}

let mockServer: FakeServer;

jest.mock('../../services/messagesApi', () => ({
  messagesApi: new Proxy({}, { get: (_target, name: string) => (...args: unknown[]) =>
    (mockServer.api as unknown as Record<string, (...rest: unknown[]) => unknown>)[name](...args) }),
}));

let nextDeviceId = 1;

async function addDevice(userId: number): Promise<FakeDevice> {
  const store = memoryKeyStore();
  const vault = createDeviceVault(store);
  const { publicKey } = await vault.createIdentity();
  const prepared = await vault.prepareDevice(userId, 4);
  const deviceId = nextDeviceId++;
  const local = await vault.commitDevice(deviceId, 1);
  const engine = createMessagingEngine(store, memoryArchive(), serial);
  const client = { ...vault, ...engine } as unknown as WorkerClient;
  const device: FakeDevice = {
    userId,
    deviceId,
    identityKey: toBase64(publicKey),
    messenger: createMessenger(client, userId, async () => local as LocalDevice),
    entry: {
      id: deviceId, signatureKey: toBase64(prepared.signatureKey), credential: toBase64(prepared.credential),
      identitySignature: toBase64(prepared.identitySignature), revoked: false,
    },
    packages: prepared.keyPackages.filter((upload) => !upload.lastResort).map((upload) => toBase64(upload.data)),
  };
  mockServer.devices.push(device);
  if (!mockServer.memberIds.includes(userId)) mockServer.memberIds.push(userId);
  return device;
}

const texts = async (device: FakeDevice) =>
  (await device.messenger.history(7, Infinity, 100)).filter((item) => item.kind === 'text').map((item) => item.text);

describe('the messenger', () => {
  beforeEach(() => {
    mockServer = new FakeServer();
  });

  test('founds the group on first sync, the other device joins from its welcome, and messages travel both ways', async () => {
    const ania = await addDevice(1);
    const bartek = await addDevice(2);

    expect((await ania.messenger.sync(mockServer.conversation())).outcome).toBe('synced');
    expect(mockServer.epoch).toBe(1);
    expect(await bartek.messenger.acceptWelcomes()).toEqual([7]);
    expect(mockServer.welcomes).toEqual([]);

    await ania.messenger.send(mockServer.conversation(), textPayload('hello', null));
    const atBartek = await bartek.messenger.sync(mockServer.conversation());
    expect(atBartek.items.map((item) => item.text)).toEqual(['hello']);

    const [hello] = atBartek.items;
    await bartek.messenger.send(mockServer.conversation(), textPayload('hi back', hello.id));
    await ania.messenger.sync(mockServer.conversation());
    expect(await texts(ania)).toEqual(['hello', 'hi back']);
    expect((await ania.messenger.history(7, Infinity, 10))[1].replyTo).toBe(hello.id);
  });

  test('a device without a welcome in a group that already has commits waits', async () => {
    const ania = await addDevice(1);
    await ania.messenger.sync(mockServer.conversation());
    const late = await addDevice(2);

    expect((await late.messenger.sync(mockServer.conversation())).outcome).toBe('waiting');
  });

  test('sending into a group that changed adds the newcomer first and shares the history the setting allows', async () => {
    const ania = await addDevice(1);
    const bartek = await addDevice(2);
    await ania.messenger.sync(mockServer.conversation());
    await bartek.messenger.acceptWelcomes();
    await ania.messenger.send(mockServer.conversation(), textPayload('before celina', null));

    mockServer.historyVisibility = 'ALL';
    const celina = await addDevice(3);
    mockServer.needsCommit = true;
    ania.messenger.forgetDirectory(7);
    await ania.messenger.send(mockServer.conversation(), textPayload('welcome celina', null));

    expect(await celina.messenger.acceptWelcomes()).toEqual([7]);
    await celina.messenger.sync(mockServer.conversation());
    expect(await texts(celina)).toEqual(['before celina', 'welcome celina']);
    await bartek.messenger.sync(mockServer.conversation());
    expect(await texts(bartek)).toEqual(['before celina', 'welcome celina']);
  });

  test('a newcomer to a joiners-only group sees nothing from before', async () => {
    const ania = await addDevice(1);
    await ania.messenger.sync(mockServer.conversation());
    await ania.messenger.send(mockServer.conversation(), textPayload('private past', null));
    const bartek = await addDevice(2);
    mockServer.needsCommit = true;
    ania.messenger.forgetDirectory();
    await ania.messenger.send(mockServer.conversation(), textPayload('from now on', null));

    await bartek.messenger.acceptWelcomes();
    await bartek.messenger.sync(mockServer.conversation());

    expect(await texts(bartek)).toEqual(['from now on']);
  });

  test('a refused send catches up and tries again, and keeps refusing only so long', async () => {
    const ania = await addDevice(1);
    await ania.messenger.sync(mockServer.conversation());

    mockServer.refuseNextPost = true;
    await ania.messenger.send(mockServer.conversation(), textPayload('second try', null));
    expect(await texts(ania)).toEqual(['second try']);

    mockServer.api.post = async () => {
      throw mockServer.stale();
    };
    await expect(ania.messenger.send(mockServer.conversation(), textPayload('never', null)))
      .rejects.toMatchObject({ code: 'STALE_EPOCH' });
  });

  test('a rename founds a new group with its name, and a later rename travels to everyone', async () => {
    const ania = await addDevice(1);
    const bartek = await addDevice(2);

    await ania.messenger.rename(mockServer.conversation(), 'Launch');
    await bartek.messenger.acceptWelcomes();
    expect(await bartek.messenger.groupNames([7])).toEqual({ 7: 'Launch' });

    await bartek.messenger.rename(mockServer.conversation(), 'Relaunch');
    await ania.messenger.sync(mockServer.conversation());
    expect(await ania.messenger.groupNames([7])).toEqual({ 7: 'Relaunch' });
  });

  test('both sides see the same security code, a mark sticks, and a new device of a contact is noticed', async () => {
    const ania = await addDevice(1);
    const bartek = await addDevice(2);
    await ania.messenger.sync(mockServer.conversation());

    const seenByAnia = await ania.messenger.verification(7, 2);
    const seenByBartek = await bartek.messenger.verification(7, 1);
    expect(seenByAnia?.digits).toHaveLength(60);
    expect(seenByAnia?.digits).toBe(seenByBartek?.digits);
    await ania.messenger.setVerified(2, seenByAnia!.identityKey, true);
    expect((await ania.messenger.verification(7, 2))?.verified).toBe(true);
    expect(await ania.messenger.verification(7, 99)).toBeNull();

    expect(await ania.messenger.checkTrust(mockServer.conversation())).toEqual([]);
    const { entryHash } = await import('../../crypto/keyLog');
    const subject = new Uint8Array(32).fill(5);
    mockServer.keyLogs[2] = [{ id: 1, change: 'DEVICE_ADDED', subject: toBase64(subject), prevHash: null,
      entryHash: toBase64(await entryHash(null, 'DEVICE_ADDED', subject)), at: '2026-11-19T10:00:00Z' }];
    const notices = await ania.messenger.checkTrust(mockServer.conversation());
    expect(notices.map((notice) => notice.notice)).toEqual([{ code: 'device-added', userId: 2 }]);
  });

  test('a device that is not this account\'s is not used, and a missing conversation reads as gone', async () => {
    const ania = await addDevice(1);
    const stranger = createMessenger({} as WorkerClient, 2, async () => ({ userId: 1, deviceId: ania.deviceId,
      signatureKey: new Uint8Array(), boundSessionId: 1 }));

    expect(await stranger.device()).toBeNull();
    expect(await stranger.acceptWelcomes()).toEqual([]);
    expect(await stranger.groupNames([7])).toEqual({});
    expect(await stranger.checkTrust(mockServer.conversation())).toEqual([]);
    await expect(stranger.sync(mockServer.conversation())).rejects.toThrow('no messaging device');
    expect(isGone(new ApiError('gone', { status: 404 }))).toBe(true);
    expect(isGone(new Error('other'))).toBe(false);
  });
});
