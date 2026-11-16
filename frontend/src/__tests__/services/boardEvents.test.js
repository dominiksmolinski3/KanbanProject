import BoardEvents from '../../services/boardEvents';
import { joinLiveSocket, leaveLiveSocket } from '../../services/liveSocket';
import { clientInstances } from '../../test-utils/stompMock';

jest.mock('@stomp/stompjs', () => require('../../test-utils/stompMock'));

jest.mock('../../services/apiInterceptor', () => ({
  renewOrSignOut: () => Promise.resolve(null),
}));

jest.mock('../../services/session', () => ({
  getAccessToken: () => 'jwt-fresh',
  isAccessTokenExpired: () => false,
  refreshSession: () => Promise.resolve(),
}));

describe('BoardEvents', () => {
  let watchers;

  beforeEach(() => {
    clientInstances.length = 0;
    watchers = [];
    jest.clearAllMocks();
  });

  afterEach(() => {
    watchers.forEach((events) => events.stop());
  });

  const boardTopic = (client) => client.subscriptions.findLast((s) => s.destination.startsWith('/topic/'));

  const watch = async (boardId = 7, onEvent = jest.fn(), onBoardsChanged = undefined, onLive = undefined) => {
    const events = new BoardEvents();
    watchers.push(events);
    events.watch(boardId, onEvent, onBoardsChanged, onLive);
    const client = clientInstances[0];
    await client.completeConnect();
    return { events, client, onEvent };
  };

  it('subscribes to the board it was asked to watch, and to its own boards notices', async () => {
    const { client } = await watch(7);

    expect(client.destinations()).toEqual(['/user/queue/boards', '/topic/boards.7']);
  });

  it('hands a boards notice to the caller', async () => {
    const onBoardsChanged = jest.fn();
    const { client } = await watch(7, jest.fn(), onBoardsChanged);

    client.subscriptionTo('/user/queue/boards').handler({ body: '{"type":"BOARDS"}' });

    expect(onBoardsChanged).toHaveBeenCalledTimes(1);
  });

  it('hands the parsed event to the caller', async () => {
    const { client, onEvent } = await watch();

    boardTopic(client).handler({ body: JSON.stringify({ type: 'TASKS', boardId: 7 }) });

    expect(onEvent).toHaveBeenCalledWith({ type: 'TASKS', boardId: 7 });
  });

  it('survives a frame it cannot parse rather than throwing into the broker callback', async () => {
    const { client, onEvent } = await watch();

    expect(() => boardTopic(client).handler({ body: 'not json' })).not.toThrow();
    expect(onEvent).not.toHaveBeenCalled();
  });

  it('says the board is live only once the broker confirms the subscription', async () => {
    const onLive = jest.fn();
    const { client } = await watch(7, jest.fn(), undefined, onLive);

    expect(onLive).not.toHaveBeenCalled();
    client.confirm(boardTopic(client));

    expect(onLive.mock.calls).toEqual([['board']]);
  });

  it('says its boards notices are live once the broker confirms them', async () => {
    const onLive = jest.fn();
    const { client } = await watch(7, jest.fn(), undefined, onLive);

    client.confirm(client.subscriptionTo('/user/queue/boards'));

    expect(onLive.mock.calls).toEqual([['boards']]);
  });

  it('keeps its boards notices open across a board switch', async () => {
    const onLive = jest.fn();
    const { events, client } = await watch(7, jest.fn(), undefined, onLive);
    const notices = client.subscriptionTo('/user/queue/boards');

    events.watch(9, jest.fn(), undefined, onLive);
    client.confirm(notices);

    expect(notices.unsubscribe).not.toHaveBeenCalled();
    expect(client.destinations()).toEqual(['/user/queue/boards', '/topic/boards.7', '/topic/boards.9']);
    expect(onLive.mock.calls).toEqual([['boards']]);
  });

  it('ignores the confirmation of boards notices from a lost connection', async () => {
    const onLive = jest.fn();
    const { client } = await watch(7, jest.fn(), undefined, onLive);
    const stale = client.subscriptionTo('/user/queue/boards');
    client.connected = false;
    await client.completeConnect();

    client.confirm(stale);

    expect(onLive).not.toHaveBeenCalled();
  });

  it('says so again after a reconnect, when events may have been missed', async () => {
    const onLive = jest.fn();
    const { client } = await watch(7, jest.fn(), undefined, onLive);
    client.confirm(boardTopic(client));
    client.connected = false;

    await client.completeConnect();
    client.confirm(boardTopic(client));

    expect(onLive).toHaveBeenCalledTimes(2);
  });

  it('ignores the confirmation of a board it has moved on from', async () => {
    const onLive = jest.fn();
    const { events, client } = await watch(7, jest.fn(), undefined, jest.fn());
    const first = boardTopic(client);
    events.watch(9, jest.fn(), undefined, onLive);

    client.confirm(first);
    expect(onLive).not.toHaveBeenCalled();

    client.confirm(boardTopic(client));
    expect(onLive).toHaveBeenCalledTimes(1);
  });

  it('resubscribes after a reconnect, or the board goes quiet without looking broken', async () => {
    const { client } = await watch(7);
    client.connected = false;

    await client.completeConnect();

    expect(client.destinations().filter((d) => d.startsWith('/topic/'))).toEqual([
      '/topic/boards.7',
      '/topic/boards.7',
    ]);
  });

  it('leaves the handles of a lost connection alone, whose ids the new connection hands out again', async () => {
    const { client } = await watch(7);
    const stale = [...client.subscriptions];
    client.connected = false;

    await client.completeConnect();

    stale.forEach((subscription) => expect(subscription.unsubscribe).not.toHaveBeenCalled());
  });

  it('moves the subscription on a board switch and keeps the one connection', async () => {
    const { events, client } = await watch(7);
    const first = boardTopic(client);

    events.watch(9, jest.fn());

    expect(first.unsubscribe).toHaveBeenCalled();
    expect(client.subscriptions.at(-1).destination).toBe('/topic/boards.9');
    expect(clientInstances).toHaveLength(1);
  });

  it('waits for the connection when the board is switched before it is up', async () => {
    const events = new BoardEvents();
    watchers.push(events);
    events.watch(7, jest.fn());
    const client = clientInstances[0];

    expect(() => events.watch(9, jest.fn())).not.toThrow();
    expect(client.subscriptions).toEqual([]);

    await client.completeConnect();
    expect(client.destinations()).toEqual(['/user/queue/boards', '/topic/boards.9']);
  });

  it('subscribes at once on a connection somebody else already opened', async () => {
    const other = { onConnect: jest.fn() };
    const client = joinLiveSocket(other);
    await client.completeConnect();
    const events = new BoardEvents();
    watchers.push(events);

    events.watch(7, jest.fn());

    expect(clientInstances).toHaveLength(1);
    expect(client.destinations()).toEqual(['/user/queue/boards', '/topic/boards.7']);
    leaveLiveSocket(other);
  });

  it('stops cleanly and lets go of the connection', async () => {
    const { events, client } = await watch();
    const subscription = boardTopic(client);
    const notices = client.subscriptionTo('/user/queue/boards');

    events.stop();

    expect(subscription.unsubscribe).toHaveBeenCalled();
    expect(notices.unsubscribe).toHaveBeenCalled();
    expect(client.deactivate).toHaveBeenCalled();
    expect(events.client).toBeNull();
  });

  it('stops without closing a connection somebody else still uses', async () => {
    const { events, client } = await watch();
    const other = { onConnect: jest.fn() };
    joinLiveSocket(other);

    events.stop();

    expect(boardTopic(client).unsubscribe).toHaveBeenCalled();
    expect(client.deactivate).not.toHaveBeenCalled();
    leaveLiveSocket(other);
  });

  it('stops without unsubscribing on a connection that is down, where the library throws', async () => {
    const { events, client } = await watch();
    const subscription = boardTopic(client);
    const notices = client.subscriptionTo('/user/queue/boards');
    client.connected = false;

    events.stop();

    expect(subscription.unsubscribe).not.toHaveBeenCalled();
    expect(notices.unsubscribe).not.toHaveBeenCalled();
    expect(client.deactivate).toHaveBeenCalled();
  });
});
