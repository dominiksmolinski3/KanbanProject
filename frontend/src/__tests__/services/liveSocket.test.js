import { joinLiveSocket, leaveLiveSocket } from '../../services/liveSocket';
import { clientInstances } from '../../test-utils/stompMock';

jest.mock('@stomp/stompjs', () => require('../../test-utils/stompMock'));

const mockRenewOrSignOut = jest.fn(() => Promise.resolve(null));
jest.mock('../../services/apiInterceptor', () => ({
  renewOrSignOut: () => mockRenewOrSignOut(),
}));

const session = { expired: false, token: 'jwt-fresh', refreshes: 0 };
jest.mock('../../services/session', () => ({
  getAccessToken: () => session.token,
  isAccessTokenExpired: () => session.expired,
  refreshSession: () => {
    session.refreshes += 1;
    session.token = 'jwt-renewed';
    session.expired = false;
    return Promise.resolve();
  },
}));

describe('the live socket', () => {
  let joined;

  const user = () => {
    const created = { onConnect: jest.fn(), onError: jest.fn() };
    joined.push(created);
    return created;
  };

  beforeEach(() => {
    clientInstances.length = 0;
    joined = [];
    session.expired = false;
    session.token = 'jwt-fresh';
    session.refreshes = 0;
    jest.clearAllMocks();
  });

  afterEach(() => {
    joined.forEach(leaveLiveSocket);
  });

  it('opens one connection, however many parts of the page use it', async () => {
    const board = user();
    const chat = user();

    const first = joinLiveSocket(board);
    const second = joinLiveSocket(chat);

    expect(second).toBe(first);
    expect(clientInstances).toHaveLength(1);
    expect(first.activate).toHaveBeenCalledTimes(1);
  });

  it('opens a plain WebSocket on the page origin rather than a hardcoded host', () => {
    const client = joinLiveSocket(user());

    expect(client.brokerURL).toBe(`${window.location.origin.replace(/^http/, 'ws')}/ws/websocket`);
    expect(client.webSocketFactory).toBeUndefined();
  });

  it('tells every user when the connection is up, and again after a reconnect', async () => {
    const board = user();
    const chat = user();
    const client = joinLiveSocket(board);
    joinLiveSocket(chat);

    await client.completeConnect();
    client.connected = false;
    await client.completeConnect();

    expect(board.onConnect).toHaveBeenCalledTimes(2);
    expect(chat.onConnect).toHaveBeenCalledTimes(2);
    expect(chat.onConnect).toHaveBeenLastCalledWith(client);
  });

  it('tells a user that joins a connection already up at once, and only that user', async () => {
    const board = user();
    const client = joinLiveSocket(board);
    await client.completeConnect();
    const chat = user();

    joinLiveSocket(chat);

    expect(chat.onConnect).toHaveBeenCalledWith(client);
    expect(board.onConnect).toHaveBeenCalledTimes(1);
  });

  it('does not tell a user that joins before the connection is up until it is', async () => {
    const client = joinLiveSocket(user());
    const late = user();

    joinLiveSocket(late);

    expect(late.onConnect).not.toHaveBeenCalled();
    await client.completeConnect();
    expect(late.onConnect).toHaveBeenCalledTimes(1);
  });

  it('stays open while anybody still uses it, and closes with the last one', async () => {
    const board = user();
    const chat = user();
    const client = joinLiveSocket(board);
    joinLiveSocket(chat);

    leaveLiveSocket(chat);
    expect(client.deactivate).not.toHaveBeenCalled();

    leaveLiveSocket(board);
    expect(client.deactivate).toHaveBeenCalledTimes(1);
  });

  it('stops telling a user that left', async () => {
    const board = user();
    const chat = user();
    const client = joinLiveSocket(board);
    joinLiveSocket(chat);

    leaveLiveSocket(chat);
    await client.completeConnect();

    expect(chat.onConnect).not.toHaveBeenCalled();
    expect(board.onConnect).toHaveBeenCalledTimes(1);
  });

  it('opens a new connection for whoever joins after the last one left', () => {
    const first = user();
    const closed = joinLiveSocket(first);
    leaveLiveSocket(first);

    const reopened = joinLiveSocket(user());

    expect(reopened).not.toBe(closed);
    expect(clientInstances).toHaveLength(2);
  });

  it('ignores a leave from somebody who never joined', () => {
    const client = joinLiveSocket(user());

    leaveLiveSocket({ onConnect: jest.fn() });

    expect(client.deactivate).not.toHaveBeenCalled();
  });

  it('takes a live token on every connection attempt, not the one it was built with', async () => {
    const client = joinLiveSocket(user());
    await client.completeConnect();
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer jwt-fresh' });

    session.expired = true;
    client.connected = false;
    await client.completeConnect();

    expect(session.refreshes).toBe(1);
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer jwt-renewed' });
  });

  it('connects without an Authorization header when there is no token to send', async () => {
    session.token = null;
    const client = joinLiveSocket(user());

    await client.completeConnect();

    expect(client.connectHeaders).toEqual({});
  });

  it('refuses to connect rather than throwing when the session cannot be renewed', async () => {
    session.expired = true;
    const failing = jest.spyOn(require('../../services/session'), 'refreshSession');
    failing.mockRejectedValueOnce(new Error('refresh refused'));
    const client = joinLiveSocket(user());

    await expect(client.completeConnect()).resolves.not.toThrow();
    failing.mockRestore();
  });

  it('renews or signs out when the server closes the connection with an ERROR, and tells its users', () => {
    const chat = user();
    const client = joinLiveSocket(chat);

    client.onStompError({ headers: { message: 'This connection was signed out' } });

    expect(mockRenewOrSignOut).toHaveBeenCalledTimes(1);
    expect(chat.onError).toHaveBeenCalledWith(expect.any(Error));
  });

  it('hands a socket failure to the users that asked to hear of one', () => {
    const chat = user();
    const quiet = { onConnect: jest.fn() };
    joined.push(quiet);
    const client = joinLiveSocket(chat);
    joinLiveSocket(quiet);
    const failure = new Error('socket closed');

    expect(() => client.onWebSocketError(failure)).not.toThrow();

    expect(chat.onError).toHaveBeenCalledWith(failure);
  });
});
