import BoardEvents from '../../services/boardEvents';

jest.mock('sockjs-client', () => jest.fn().mockImplementation((url) => ({ url })));

const clientInstances = [];
jest.mock('@stomp/stompjs', () => ({
  Client: jest.fn().mockImplementation(function Client(config) {
    Object.assign(this, config);
    this.connected = false;
    this.subscribe = jest.fn((destination, handler) => {
      const subscription = { destination, handler, unsubscribe: jest.fn() };
      this.subscriptions.push(subscription);
      return subscription;
    });
    this.subscriptions = [];
    this.activate = jest.fn();
    this.deactivate = jest.fn();
    // What SockJS does for real once the CONNECT frame is accepted, and again after a reconnect.
    this.completeConnect = async () => {
      await this.beforeConnect();
      this.connected = true;
      this.onConnect();
    };
    clientInstances.push(this);
  }),
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

describe('BoardEvents', () => {
  beforeEach(() => {
    clientInstances.length = 0;
    session.expired = false;
    session.token = 'jwt-fresh';
    session.refreshes = 0;
    jest.clearAllMocks();
  });

  const watch = async (boardId = 7, onEvent = jest.fn()) => {
    const events = new BoardEvents();
    events.watch(boardId, onEvent);
    const client = clientInstances[0];
    await client.completeConnect();
    return { events, client, onEvent };
  };

  it('subscribes to the board it was asked to watch', async () => {
    const { client } = await watch(7);

    expect(client.subscriptions.map((s) => s.destination)).toEqual(['/topic/boards/7']);
  });

  it('hands the parsed event to the caller', async () => {
    const { client, onEvent } = await watch();

    client.subscriptions[0].handler({ body: JSON.stringify({ type: 'TASKS', boardId: 7 }) });

    expect(onEvent).toHaveBeenCalledWith({ type: 'TASKS', boardId: 7 });
  });

  it('survives a frame it cannot parse rather than throwing into the broker callback', async () => {
    const { client, onEvent } = await watch();

    expect(() => client.subscriptions[0].handler({ body: 'not json' })).not.toThrow();
    expect(onEvent).not.toHaveBeenCalled();
  });

  it('takes a live token on every connection attempt, not the one it was built with', async () => {
    const { client } = await watch();
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer jwt-fresh' });

    // A socket that drops after the fifteen-minute token lapses: without beforeConnect renewing,
    // every reconnect would present the dead token and be refused, forever.
    session.expired = true;
    client.connected = false;
    await client.completeConnect();

    expect(session.refreshes).toBe(1);
    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer jwt-renewed' });
  });

  it('resubscribes after a reconnect, or the board goes quiet without looking broken', async () => {
    const { client } = await watch(7);
    client.connected = false;

    await client.completeConnect();

    expect(client.subscriptions.map((s) => s.destination)).toEqual([
      '/topic/boards/7',
      '/topic/boards/7',
    ]);
  });

  it('moves the subscription on a board switch and keeps the one connection', async () => {
    const { events, client } = await watch(7);
    const first = client.subscriptions[0];

    events.watch(9, jest.fn());

    expect(first.unsubscribe).toHaveBeenCalled();
    expect(client.subscriptions.at(-1).destination).toBe('/topic/boards/9');
    expect(clientInstances).toHaveLength(1);
  });

  it('refuses to connect rather than throwing when the session cannot be renewed', async () => {
    session.expired = true;
    const failing = jest.spyOn(require('../../services/session'), 'refreshSession');
    failing.mockRejectedValueOnce(new Error('refresh refused'));

    const events = new BoardEvents();
    events.watch(7, jest.fn());

    await expect(clientInstances[0].completeConnect()).resolves.not.toThrow();
    failing.mockRestore();
  });

  it('stops cleanly and lets go of the connection', async () => {
    const { events, client } = await watch();
    const subscription = client.subscriptions[0];

    events.stop();

    expect(subscription.unsubscribe).toHaveBeenCalled();
    expect(client.deactivate).toHaveBeenCalled();
    expect(events.client).toBeNull();
  });
});
