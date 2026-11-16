import ChatApi from '../../services/chatApi';
import BoardEvents from '../../services/boardEvents';
import { clientInstances } from '../../test-utils/stompMock';

jest.mock('@stomp/stompjs', () => require('../../test-utils/stompMock'));

jest.mock('../../services/apiInterceptor', () => ({
  renewOrSignOut: () => Promise.resolve(null),
}));

jest.mock('../../services/session', () => ({
  getAccessToken: () => 'jwt-123',
  isAccessTokenExpired: () => false,
  refreshSession: () => Promise.resolve(),
}));

describe('ChatApi', () => {
  let onMessage;
  let onError;
  let onRefusal;
  let opened;

  beforeEach(() => {
    clientInstances.length = 0;
    opened = [];
    jest.clearAllMocks();
    onMessage = jest.fn();
    onError = jest.fn();
    onRefusal = jest.fn();
  });

  afterEach(() => {
    opened.forEach((each) => (each.disconnect ? each.disconnect() : each.stop()));
  });

  const connect = async (onReady) => {
    const api = new ChatApi(onMessage, onError, onRefusal, onReady);
    opened.push(api);
    const connecting = api.connect();
    const client = clientInstances[0];
    if (!client.connected) {
      await client.completeConnect();
    }
    await connecting;
    return { api, client };
  };

  const reconnect = async (client) => {
    client.connected = false;
    await client.completeConnect();
  };

  it('subscribes to the private queue without naming the account', async () => {
    const { client } = await connect();

    expect(client.destinations()).toContain('/user/queue/messages');
    expect(client.destinations().some((d) => d.includes('@'))).toBe(false);
  });

  it('subscribes to no topic on connect - there is no global room to join', async () => {
    const { client } = await connect();

    expect(client.destinations().filter((d) => d.startsWith('/topic/'))).toEqual([]);
  });

  it('hands a direct message to the caller', async () => {
    const { client } = await connect();
    const frame = { body: '{"type":"PRIVATE"}' };

    client.subscriptionTo('/user/queue/messages').handler(frame);

    expect(onMessage).toHaveBeenCalledWith(frame);
  });

  it('hands a refusal to the caller as the parsed payload', async () => {
    const { client } = await connect();

    client.subscriptionTo('/user/queue/errors').handler({ body: JSON.stringify({ reason: 'chat.errors.tooLong' }) });

    expect(onRefusal).toHaveBeenCalledWith({ reason: 'chat.errors.tooLong' });
  });

  it('survives a refusal frame that will not parse', async () => {
    const { client } = await connect();

    expect(() => client.subscriptionTo('/user/queue/errors').handler({ body: 'not json' })).not.toThrow();
    expect(onRefusal).not.toHaveBeenCalled();
  });

  it('reports a failure of the socket and fails the connect that was waiting on it', async () => {
    const api = new ChatApi(onMessage, onError, onRefusal);
    opened.push(api);
    const connecting = api.connect();
    const failure = new Error('socket closed');

    clientInstances[0].onWebSocketError(failure);

    await expect(connecting).rejects.toBe(failure);
    expect(onError).toHaveBeenCalledWith(failure);
  });

  describe('sharing the board connection', () => {
    const watchBoard = async () => {
      const events = new BoardEvents();
      opened.push(events);
      events.watch(7, jest.fn());
      const client = clientInstances[0];
      await client.completeConnect();
      return { events, client };
    };

    it('opens no second connection when the board already holds one', async () => {
      const { client } = await watchBoard();

      const { client: chatClient } = await connect();

      expect(chatClient).toBe(client);
      expect(clientInstances).toHaveLength(1);
      expect(client.destinations()).toEqual([
        '/user/queue/boards',
        '/topic/boards.7',
        '/user/queue/messages',
        '/user/queue/errors',
      ]);
    });

    it('closes its own subscriptions and leaves the connection to the board', async () => {
      const { client } = await watchBoard();
      const { api } = await connect();
      api.joinBoard(7);

      expect(api.disconnect()).toBe(true);

      expect(client.subscriptionTo('/user/queue/messages').unsubscribe).toHaveBeenCalled();
      expect(client.subscriptionTo('/user/queue/errors').unsubscribe).toHaveBeenCalled();
      expect(client.subscriptionTo('/topic/boards.7.chat').unsubscribe).toHaveBeenCalled();
      expect(client.subscriptionTo('/topic/boards.7').unsubscribe).not.toHaveBeenCalled();
      expect(client.deactivate).not.toHaveBeenCalled();
    });

    it('hears nothing more after it disconnects, though the connection comes back', async () => {
      const { client } = await watchBoard();
      const { api } = await connect();
      api.disconnect();
      const before = client.subscriptions.length;

      await reconnect(client);

      expect(client.destinations().slice(before)).toEqual(['/user/queue/boards', '/topic/boards.7']);
    });
  });

  describe('disconnecting', () => {
    it('closes the connection when nothing else uses it', async () => {
      const { api, client } = await connect();

      expect(api.disconnect()).toBe(true);

      expect(client.deactivate).toHaveBeenCalledTimes(1);
      expect(api.isConnected()).toBe(false);
    });

    it('says so when there was nothing to disconnect', () => {
      const api = new ChatApi(onMessage, onError, onRefusal);

      expect(api.disconnect()).toBe(false);
    });

    it('sends nothing on a connection that is down, where the library throws', async () => {
      const { api, client } = await connect();
      api.joinBoard(7);
      client.connected = false;
      client.published.length = 0;

      expect(() => api.disconnect()).not.toThrow();

      expect(client.published).toEqual([]);
      expect(client.subscriptionTo('/user/queue/messages').unsubscribe).not.toHaveBeenCalled();
    });
  });

  describe('joining a board', () => {
    it('subscribes to the board chat topic and announces on it once the broker confirms', async () => {
      const { api, client } = await connect();

      api.joinBoard(7);

      expect(client.destinations()).toContain('/topic/boards.7.chat');
      expect(client.published).toEqual([]);

      client.confirm(client.subscriptionTo('/topic/boards.7.chat'));

      expect(client.published).toContainEqual({
        destination: '/app/chat.join',
        body: JSON.stringify({ boardId: 7 }),
      });
    });

    it('does not announce on a board it has already moved on from', async () => {
      const { api, client } = await connect();
      api.joinBoard(7);
      const first = client.subscriptionTo('/topic/boards.7.chat');
      api.joinBoard(8);

      client.confirm(first);

      expect(client.published).toEqual([]);
    });

    it('drops the previous board subscription rather than stacking them up', async () => {
      const { api, client } = await connect();

      api.joinBoard(7);
      const first = client.subscriptionTo('/topic/boards.7.chat');
      api.joinBoard(8);

      expect(first.unsubscribe).toHaveBeenCalled();
      expect(client.destinations()).toContain('/topic/boards.8.chat');
    });

    it('does nothing without a board to join', async () => {
      const { api, client } = await connect();

      expect(api.joinBoard(null)).toBe(false);
      expect(client.destinations().filter((d) => d.startsWith('/topic/'))).toEqual([]);
    });

    it('unsubscribes and says so when leaving', async () => {
      const { api, client } = await connect();
      api.joinBoard(7);

      api.leaveBoard(7);

      expect(client.subscriptionTo('/topic/boards.7.chat').unsubscribe).toHaveBeenCalled();
      expect(client.published).toContainEqual({
        destination: '/app/chat.leave',
        body: JSON.stringify({ boardId: 7 }),
      });
    });
  });

  describe('reconnecting', () => {
    it('joins the board it was on again once the socket is back', async () => {
      const { api, client } = await connect();
      api.joinBoard(7);
      const before = client.subscriptions.length;
      client.published.length = 0;

      await reconnect(client);

      expect(client.destinations().slice(before)).toEqual([
        '/user/queue/messages',
        '/user/queue/errors',
        '/topic/boards.7.chat',
      ]);
      client.confirm(client.subscriptionTo('/topic/boards.7.chat'));
      expect(client.published).toContainEqual({
        destination: '/app/chat.join',
        body: JSON.stringify({ boardId: 7 }),
      });
    });

    it('leaves the handles of a lost connection alone, whose ids the new connection hands out again', async () => {
      const { api, client } = await connect();
      api.joinBoard(7);
      const stale = [...client.subscriptions];

      await reconnect(client);

      stale.forEach((subscription) => expect(subscription.unsubscribe).not.toHaveBeenCalled());
    });

    it('joins no board when none was joined before the drop', async () => {
      const { client } = await connect();
      const before = client.subscriptions.length;

      await reconnect(client);

      expect(client.destinations().slice(before).filter((d) => d.startsWith('/topic/'))).toEqual([]);
    });

  });

  describe('being ready to send', () => {
    const confirmQueue = (client) => client.confirm(client.subscriptionTo('/user/queue/messages'));
    const confirmBoard = (client, boardId) => client.confirm(client.subscriptionTo(`/topic/boards.${boardId}.chat`));

    it('is ready with no board once the broker confirms the private queue', async () => {
      const onReady = jest.fn();
      const { client } = await connect(onReady);

      expect(onReady).not.toHaveBeenCalled();
      confirmQueue(client);

      expect(onReady.mock.calls).toEqual([[true]]);
    });

    it('is not ready on a board until its conversation is confirmed too', async () => {
      const onReady = jest.fn();
      const { api, client } = await connect(onReady);
      api.joinBoard(7);

      confirmQueue(client);
      expect(onReady).not.toHaveBeenCalled();

      confirmBoard(client, 7);
      expect(onReady.mock.calls).toEqual([[true]]);
    });

    it('stops being ready while it moves to another board', async () => {
      const onReady = jest.fn();
      const { api, client } = await connect(onReady);
      api.joinBoard(7);
      confirmQueue(client);
      confirmBoard(client, 7);

      api.joinBoard(8);
      expect(onReady.mock.calls).toEqual([[true], [false]]);

      confirmBoard(client, 8);
      expect(onReady.mock.calls).toEqual([[true], [false], [true]]);
    });

    it('is not ready after a reconnect until the broker confirms again', async () => {
      const onReady = jest.fn();
      const { client } = await connect(onReady);
      confirmQueue(client);
      const stale = client.subscriptionTo('/user/queue/messages');

      await reconnect(client);
      client.confirm(stale);
      expect(onReady.mock.calls).toEqual([[true], [false]]);

      confirmQueue(client);
      expect(onReady.mock.calls).toEqual([[true], [false], [true]]);
    });

    it('waits for the board again after a reconnect', async () => {
      const onReady = jest.fn();
      const { api, client } = await connect(onReady);
      api.joinBoard(7);
      confirmQueue(client);
      confirmBoard(client, 7);

      await reconnect(client);
      confirmQueue(client);
      expect(onReady.mock.calls).toEqual([[true], [false]]);

      confirmBoard(client, 7);
      expect(onReady.mock.calls).toEqual([[true], [false], [true]]);
    });
  });

  describe('sending', () => {
    it('sends a board message naming the board and nothing else', async () => {
      const { api, client } = await connect();

      api.sendMessage('board', 'hello', 7, '');

      expect(client.published).toContainEqual({
        destination: '/app/chat.sendMessage',
        body: JSON.stringify({ content: 'hello', boardId: 7 }),
      });
    });

    it('will not send a board message with no board', async () => {
      const { api, client } = await connect();

      expect(api.sendMessage('board', 'hello', null, '')).toBe(false);
      expect(client.published).toEqual([]);
    });

    it('sends a direct message naming the recipient', async () => {
      const { api, client } = await connect();

      api.sendMessage('private', 'psst', 7, 'bob@example.com');

      expect(client.published).toContainEqual({
        destination: '/app/chat.sendPrivateMessage',
        body: JSON.stringify({ content: 'psst', recipientId: 'bob@example.com' }),
      });
    });

    it('will not send a direct message with no recipient', async () => {
      const { api } = await connect();

      expect(api.sendMessage('private', 'psst', 7, '  ')).toBe(false);
    });

    it('will not send an empty message', async () => {
      const { api } = await connect();

      expect(api.sendMessage('board', '   ', 7, '')).toBe(false);
    });

    it('keeps a message while the connection is down, where the library throws', async () => {
      const { api, client } = await connect();
      client.connected = false;

      expect(api.sendMessage('board', 'hello', 7, '')).toBe(false);
      expect(client.published).toEqual([]);
    });
  });
});
