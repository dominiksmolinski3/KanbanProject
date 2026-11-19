import { messagesApi } from '../../services/messagesApi';

const ok = (body: unknown) => ({ ok: true, status: 200, json: () => Promise.resolve(body) });

const sent = (call: number) => {
  const [path, init] = (window.fetch as jest.Mock).mock.calls[call];
  return { path, method: init?.method ?? 'GET', body: init?.body ? JSON.parse(init.body) : undefined };
};

describe('messagesApi', () => {
  beforeEach(() => {
    window.fetch = jest.fn().mockResolvedValue(ok({}));
  });

  test('reads conversations, contacts, a lookup and blocks', async () => {
    await messagesApi.list();
    await messagesApi.get(5);
    await messagesApi.contacts();
    await messagesApi.lookup('a+b@example.com');
    await messagesApi.blocks();

    expect(sent(0)).toEqual({ path: '/api/conversations', method: 'GET', body: undefined });
    expect(sent(1).path).toBe('/api/conversations/5');
    expect(sent(2).path).toBe('/api/conversations/contacts');
    expect(sent(3).path).toBe('/api/conversations/lookup?email=a%2Bb%40example.com');
    expect(sent(4).path).toBe('/api/blocks');
  });

  test('starts, configures and manages the people of a conversation', async () => {
    await messagesApi.create({ kind: 'GROUP', userIds: [2, 3], historyVisibility: 'ALL' });
    await messagesApi.update(5, { muted: true });
    await messagesApi.addMembers(5, [4]);
    await messagesApi.setRole(5, 4, 'ADMIN');
    await messagesApi.removeMember(5, 4);
    await messagesApi.accept(5);
    await messagesApi.markRead(5, 9);
    await messagesApi.typing(5);

    expect(sent(0)).toEqual({ path: '/api/conversations', method: 'POST',
      body: { kind: 'GROUP', userIds: [2, 3], historyVisibility: 'ALL' } });
    expect(sent(1)).toEqual({ path: '/api/conversations/5', method: 'PATCH', body: { muted: true } });
    expect(sent(2)).toEqual({ path: '/api/conversations/5/members', method: 'POST', body: { userIds: [4] } });
    expect(sent(3)).toEqual({ path: '/api/conversations/5/members/4', method: 'PUT', body: { role: 'ADMIN' } });
    expect(sent(4)).toEqual({ path: '/api/conversations/5/members/4', method: 'DELETE', body: undefined });
    expect(sent(5)).toEqual({ path: '/api/conversations/5/accept', method: 'POST', body: undefined });
    expect(sent(6)).toEqual({ path: '/api/conversations/5/read', method: 'POST', body: { seq: 9 } });
    expect(sent(7)).toEqual({ path: '/api/conversations/5/typing', method: 'POST', body: undefined });
  });

  test('carries the MLS traffic: directory, claims, commits, messages and welcomes', async () => {
    await messagesApi.directory(5);
    await messagesApi.claim(5, [20, 21]);
    await messagesApi.commit(5, { deviceId: 10, epoch: 1, body: 'AQ==', welcome: null, welcomeDeviceIds: [] });
    await messagesApi.post(5, { deviceId: 10, epoch: 2, body: 'Ag==' });
    await messagesApi.messages(5, 7);
    await messagesApi.welcomes(10);
    await messagesApi.dropWelcome(10, 3);
    await messagesApi.keyLog(2, 4);

    expect(sent(0).path).toBe('/api/conversations/5/devices');
    expect(sent(1)).toEqual({ path: '/api/conversations/5/key-packages', method: 'POST', body: { deviceIds: [20, 21] } });
    expect(sent(2)).toEqual({ path: '/api/conversations/5/commits', method: 'POST',
      body: { deviceId: 10, epoch: 1, body: 'AQ==', welcome: null, welcomeDeviceIds: [] } });
    expect(sent(3)).toEqual({ path: '/api/conversations/5/messages', method: 'POST', body: { deviceId: 10, epoch: 2, body: 'Ag==' } });
    expect(sent(4).path).toBe('/api/conversations/5/messages?after=7&size=100');
    expect(sent(5).path).toBe('/api/crypto/devices/10/welcomes');
    expect(sent(6)).toEqual({ path: '/api/crypto/devices/10/welcomes/3', method: 'DELETE', body: undefined });
    expect(sent(7).path).toBe('/api/crypto/users/2/key-log?after=4');
  });

  test('blocks and unblocks by account id', async () => {
    await messagesApi.block(2);
    await messagesApi.unblock(2);

    expect(sent(0)).toEqual({ path: '/api/blocks', method: 'POST', body: { userId: 2 } });
    expect(sent(1)).toEqual({ path: '/api/blocks/2', method: 'DELETE', body: undefined });
  });
});
