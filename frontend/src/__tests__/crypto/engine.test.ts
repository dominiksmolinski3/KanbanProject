/**
 * @jest-environment node
 */
import { memoryArchive } from '../../crypto/archive';
import { toBase64 } from '../../crypto/bytes';
import { createDeviceVault } from '../../crypto/deviceVault';
import {
  createMessagingEngine,
  type DirectoryMember,
  type Lock,
  type MessagingEngine,
  type ServerMessage,
} from '../../crypto/engine';
import { memoryKeyStore } from '../../crypto/keyStore';
import type { Payload } from '../../crypto/payload';

const serial: Lock = (work) => work();

interface Device {
  userId: number;
  deviceId: number;
  engine: MessagingEngine;
  directoryEntry: DirectoryMember['devices'][number];
  identityKey: string;
  packages: string[];
  recovery: { key: string; wrappedPrivate: Uint8Array; publicKey: Uint8Array };
}

let nextDeviceId = 1;
const RECOVERY_KEY = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789ABCDEFGHJKLMNPQR';

async function newDevice(userId: number, sameAccountAs?: Device): Promise<Device> {
  const store = memoryKeyStore();
  const vault = createDeviceVault(store);
  let recovery: Device['recovery'];
  if (sameAccountAs) {
    recovery = sameAccountAs.recovery;
    await vault.restoreWithRecoveryKey(recovery.key,
      { publicKey: recovery.publicKey, wrappedPrivate: recovery.wrappedPrivate });
  } else {
    const { publicKey } = await vault.createIdentity();
    recovery = { key: RECOVERY_KEY, publicKey, wrappedPrivate: (await vault.wrapForRecovery(RECOVERY_KEY)).wrappedPrivate };
  }
  const prepared = await vault.prepareDevice(userId, 5);
  const deviceId = nextDeviceId++;
  await vault.commitDevice(deviceId, 1);
  return {
    userId,
    deviceId,
    identityKey: toBase64(recovery.publicKey),
    recovery,
    engine: createMessagingEngine(store, memoryArchive(), serial),
    directoryEntry: {
      id: deviceId,
      signatureKey: toBase64(prepared.signatureKey),
      credential: toBase64(prepared.credential),
      identitySignature: toBase64(prepared.identitySignature),
      revoked: false,
    },
    packages: prepared.keyPackages.filter((upload) => !upload.lastResort).map((upload) => toBase64(upload.data)),
  };
}

class FakeServer {
  epoch = 0;
  messages: ServerMessage[] = [];
  welcomes = new Map<number, { seq: number; body: string }>();
  members: Device[][] = [];

  directory(): DirectoryMember[] {
    return this.members.map((devices) => ({
      userId: devices[0].userId,
      identityKey: devices[0].identityKey,
      devices: devices.map((device) => device.directoryEntry),
    }));
  }

  claim(deviceIds: number[]) {
    return this.members.flat()
      .filter((device) => deviceIds.includes(device.deviceId) && device.packages.length)
      .map((device) => ({ deviceId: device.deviceId, keyPackage: device.packages.shift()! }));
  }

  commit(sender: Device, epoch: number, commit: string, welcome: string | null, welcomeDeviceIds: number[]) {
    if (epoch !== this.epoch) return null;
    this.epoch++;
    const seq = this.messages.length + 1;
    this.messages.push({ seq, epoch, kind: 'COMMIT', senderId: sender.userId, senderDeviceId: sender.deviceId,
      body: commit, createdAt: new Date(seq * 1000).toISOString() });
    welcomeDeviceIds.forEach((id) => this.welcomes.set(id, { seq, body: welcome! }));
    return seq;
  }

  post(sender: Device, epoch: number, body: string) {
    if (epoch !== this.epoch) return null;
    const seq = this.messages.length + 1;
    this.messages.push({ seq, epoch, kind: 'APPLICATION', senderId: sender.userId, senderDeviceId: sender.deviceId,
      body, createdAt: new Date(seq * 1000).toISOString() });
    return seq;
  }
}

const CONVERSATION = 7;
const GROUP_ID = toBase64(new TextEncoder().encode('conversation-7'));

async function reconcile(server: FakeServer, device: Device, name?: string) {
  const status = await device.engine.groupStatus(CONVERSATION);
  const directory = server.directory();
  const wanted = status.hasGroup
    ? (await device.engine.planChanges(CONVERSATION, directory)).addDeviceIds
    : await device.engine.liveDeviceIds(directory);
  const prepared = await device.engine.prepareCommit(CONVERSATION, directory, {
    groupId: status.hasGroup ? undefined : GROUP_ID,
    keyPackages: server.claim(wanted),
    name,
  });
  const seq = server.commit(device, prepared.epoch, prepared.commit, prepared.welcome, prepared.welcomeDeviceIds);
  if (seq === null) {
    await device.engine.discardCommit(CONVERSATION);
    return null;
  }
  await device.engine.confirmCommit(CONVERSATION, seq, new Date().toISOString());
  return { seq, welcomed: prepared.welcomeDeviceIds };
}

async function join(server: FakeServer, device: Device) {
  const welcome = server.welcomes.get(device.deviceId)!;
  await device.engine.acceptWelcome(CONVERSATION, welcome.seq, welcome.body, server.directory());
}

async function send(server: FakeServer, device: Device, payload: Payload) {
  const encrypted = await device.engine.encryptPayload(CONVERSATION, payload);
  const seq = server.post(device, encrypted.epoch, encrypted.body);
  if (seq === null) return null;
  await device.engine.recordSent(CONVERSATION, seq, payload, device.userId, new Date().toISOString());
  return seq;
}

const sync = (server: FakeServer, device: Device) =>
  device.engine.receiveMessages(CONVERSATION, server.messages, server.directory(), device.deviceId);

const texts = async (device: Device) =>
  (await device.engine.readArchive(CONVERSATION, Infinity, 100)).filter((m) => m.kind === 'text').map((m) => m.text);

describe('the messaging engine', () => {
  test('two people talk, react, edit and delete, and nobody can edit somebody else\'s message', async () => {
    const server = new FakeServer();
    const alice = await newDevice(1);
    const bob = await newDevice(2);
    server.members = [[alice], [bob]];

    const founded = await reconcile(server, alice);
    expect(founded?.welcomed).toEqual([bob.deviceId]);
    await join(server, bob);

    await send(server, alice, { t: 'text', id: 'm1', text: 'hello Bob' });
    const atBob = await sync(server, bob);
    expect(atBob.items.map((item) => [item.senderId, item.text])).toEqual([[1, 'hello Bob']]);

    await send(server, bob, { t: 'react', id: 'r1', target: 'm1', emoji: '👍', on: true });
    await send(server, bob, { t: 'edit', id: 'e1', target: 'm1', text: 'forged' });
    await send(server, alice, { t: 'edit', id: 'e2', target: 'm1', text: 'hello, Bob' });
    await sync(server, alice);
    await sync(server, bob);

    for (const device of [alice, bob]) {
      const [message] = await device.engine.readArchive(CONVERSATION, Infinity, 10);
      expect(message.text).toBe('hello, Bob');
      expect(message.editedAt).not.toBeNull();
      expect(message.reactions).toEqual({ '👍': [2] });
    }

    await send(server, alice, { t: 'delete', id: 'd1', target: 'm1' });
    await sync(server, bob);
    const [deleted] = await bob.engine.readArchive(CONVERSATION, Infinity, 10);
    expect(deleted).toMatchObject({ deleted: true, text: '', reactions: {} });
  });

  test('a new device of a member is added by someone else, and receives the history of that account', async () => {
    const server = new FakeServer();
    const alice = await newDevice(1);
    const bob = await newDevice(2);
    server.members = [[alice], [bob]];
    await reconcile(server, alice, 'Launch');
    await join(server, bob);
    await send(server, alice, { t: 'text', id: 'm1', text: 'first' });
    await send(server, bob, { t: 'text', id: 'm2', text: 'second' });
    await sync(server, bob);

    const alice2 = await newDevice(1, alice);
    server.members[0] = [alice, alice2];
    const plan = await bob.engine.planChanges(CONVERSATION, server.directory());
    expect(plan).toEqual({ addDeviceIds: [alice2.deviceId], removals: 0 });

    const added = await reconcile(server, bob);
    expect(added?.welcomed).toEqual([alice2.deviceId]);
    await join(server, alice2);
    const credentials = await bob.engine.credentialsOf(server.directory(), added!.welcomed);
    for (const chunk of await bob.engine.historyFor(CONVERSATION, null, credentials)) {
      await send(server, bob, chunk);
    }
    await sync(server, alice2);
    await sync(server, alice);

    expect(await texts(alice2)).toEqual(['first', 'second']);
    expect(await texts(alice)).toEqual(['first', 'second']);
  });

  test('two commits race for one epoch: the loser drops its own and takes the winner\'s', async () => {
    const server = new FakeServer();
    const alice = await newDevice(1);
    const bob = await newDevice(2);
    server.members = [[alice], [bob]];
    await reconcile(server, alice, 'Team');
    await join(server, bob);

    const directory = server.directory();
    const fromAlice = await alice.engine.prepareCommit(CONVERSATION, directory, { keyPackages: [], name: 'Alice wins' });
    const fromBob = await bob.engine.prepareCommit(CONVERSATION, directory, { keyPackages: [], name: 'Bob loses' });
    const seq = server.commit(alice, fromAlice.epoch, fromAlice.commit, null, []);
    expect(server.commit(bob, fromBob.epoch, fromBob.commit, null, [])).toBeNull();
    await bob.engine.discardCommit(CONVERSATION);
    await alice.engine.confirmCommit(CONVERSATION, seq!, new Date().toISOString());
    await sync(server, bob);

    expect((await bob.engine.groupStatus(CONVERSATION)).name).toBe('Alice wins');
    expect((await alice.engine.groupStatus(CONVERSATION)).epoch).toBe(2);
    await send(server, bob, { t: 'text', id: 'after', text: 'still talking' });
    expect((await sync(server, alice)).items.map((item) => item.text)).toEqual(['still talking']);
  });

  test('a message sent between a sync and a commit is read with the old epoch before the commit is adopted', async () => {
    const server = new FakeServer();
    const alice = await newDevice(1);
    const bob = await newDevice(2);
    server.members = [[alice], [bob]];
    await reconcile(server, alice, 'Before');
    await join(server, bob);

    const prepared = await alice.engine.prepareCommit(CONVERSATION, server.directory(), { keyPackages: [], name: 'After' });
    await send(server, bob, { t: 'text', id: 'between', text: 'sent in the gap' });
    server.epoch = prepared.epoch;
    const seq = server.commit(alice, prepared.epoch, prepared.commit, null, []);
    await alice.engine.confirmCommit(CONVERSATION, seq!, new Date().toISOString());
    expect((await alice.engine.groupStatus(CONVERSATION)).name).toBe('Before');

    const received = await sync(server, alice);

    expect(received.items.filter((item) => item.kind === 'text').map((item) => item.text)).toEqual(['sent in the gap']);
    expect((await alice.engine.groupStatus(CONVERSATION)).name).toBe('After');
  });

  test('a removed member is told so, and the archive keeps only what it could read', async () => {
    const server = new FakeServer();
    const alice = await newDevice(1);
    const bob = await newDevice(2);
    const carol = await newDevice(3);
    server.members = [[alice], [bob], [carol]];
    await reconcile(server, alice, 'Three');
    await join(server, bob);
    await join(server, carol);
    await send(server, alice, { t: 'text', id: 'm1', text: 'all three' });

    server.members = [[alice], [bob]];
    const removal = await reconcile(server, alice);
    expect(removal).not.toBeNull();
    await send(server, alice, { t: 'text', id: 'm2', text: 'just us' });

    const atCarol = await sync(server, carol);
    const atBob = await sync(server, bob);

    expect(atCarol.removed).toBe(true);
    expect(await texts(carol)).toEqual(['all three']);
    expect(await texts(bob)).toEqual(['all three', 'just us']);
    expect(atBob.items.filter((item) => item.kind === 'notice').map((item) => item.notice)).toEqual(
      [{ code: 'left', userId: 3 }]);
  });

  test('a device that joins later is sent the history it was not there for', async () => {
    const server = new FakeServer();
    const alice = await newDevice(1);
    const bob = await newDevice(2);
    server.members = [[alice], [bob]];
    await reconcile(server, alice);
    await join(server, bob);
    await send(server, alice, { t: 'text', id: 'm1', text: 'before dave' });
    await send(server, bob, { t: 'text', id: 'm2', text: 'also before' });
    await sync(server, alice);

    const dave = await newDevice(4);
    server.members.push([dave]);
    const added = await reconcile(server, alice);
    expect(added?.welcomed).toEqual([dave.deviceId]);
    await join(server, dave);
    const credentials = await alice.engine.credentialsOf(server.directory(), [dave.deviceId]);
    for (const chunk of await alice.engine.historyFor(CONVERSATION, null, credentials)) {
      await send(server, alice, chunk);
    }

    await sync(server, dave);
    await sync(server, bob);

    expect(await texts(dave)).toEqual(['before dave', 'also before']);
    expect(await texts(bob)).toEqual(['before dave', 'also before']);
  });

  test('a message whose signature names another device is shown as unreadable', async () => {
    const server = new FakeServer();
    const alice = await newDevice(1);
    const bob = await newDevice(2);
    server.members = [[alice], [bob]];
    await reconcile(server, alice);
    await join(server, bob);
    server.members = [[{ ...alice, directoryEntry: { ...alice.directoryEntry, signatureKey: bob.directoryEntry.signatureKey } }],
      [bob]];

    await send(server, alice, { t: 'text', id: 'm1', text: 'hi' });
    const atBob = await sync(server, bob);

    expect(atBob.items.map((item) => item.kind)).toEqual(['notice']);
  });
});

