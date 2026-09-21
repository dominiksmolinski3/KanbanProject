import ChatApi from '../../services/chatApi';

// Each of these stood between the chat feature and a single working connection.
jest.mock('sockjs-client', () => jest.fn().mockImplementation((url) => ({ url })));

const clientInstances = [];
jest.mock('@stomp/stompjs', () => ({
  Client: jest.fn().mockImplementation(function Client(config) {
    Object.assign(this, config);
    this.active = false;
    this.subscriptions = [];
    this.published = [];
    this.activate = jest.fn(() => {
      this.active = true;
      this.onConnect();
    });
    this.subscribe = jest.fn((destination, handler) => {
      this.subscriptions.push(destination);
      return { unsubscribe: jest.fn(), destination, handler };
    });
    this.publish = jest.fn((frame) => this.published.push(frame));
    this.deactivate = jest.fn();
    clientInstances.push(this);
  }),
}));

import SockJS from 'sockjs-client';

describe('ChatApi', () => {
  let onMessage;
  let onError;
  let onRefusal;

  beforeEach(() => {
    clientInstances.length = 0;
    jest.clearAllMocks();
    onMessage = jest.fn();
    onError = jest.fn();
    onRefusal = jest.fn();
  });

  const connect = async (username = 'ada@example.com', ...token) => {
    const api = new ChatApi(onMessage, onError, onRefusal);
    await api.connect(username, token.length ? token[0] : 'jwt-123');
    return { api, client: clientInstances[0] };
  };

  it('sends the JWT on the CONNECT frame, which the interceptor requires', async () => {
    const { client } = await connect();

    expect(client.connectHeaders).toEqual({ Authorization: 'Bearer jwt-123' });
  });

  it('connects to /ws on the page origin rather than a hardcoded host', async () => {
    const { client } = await connect();

    client.webSocketFactory();

    // The deployed app is not on localhost:8080, and in dev Vite proxies /ws to it.
    expect(SockJS).toHaveBeenCalledWith(`${window.location.origin}/ws`);
  });

  it('subscribes to the private queue under the name the server knows', async () => {
    const { client } = await connect('ada@example.com');

    expect(client.subscriptions).toContain('/user/ada@example.com/queue/messages');
  });

  /*
   * The finding, as an assertion: connecting used to put every account on one global topic, so a
   * member of one board read the messages of every other board's members.
   */
  it('subscribes to no topic on connect - there is no global room to join', async () => {
    const { client } = await connect('ada@example.com');

    expect(client.subscriptions.filter((d) => d.startsWith('/topic/'))).toEqual([]);
    expect(client.subscriptions).not.toContain('/topic/public');
  });

  it('subscribes to the error queue, which is how a refusal arrives without closing the session', async () => {
    const { client } = await connect('ada@example.com');

    expect(client.subscriptions).toContain('/user/ada@example.com/queue/errors');
  });

  it('hands a refusal to the caller as the parsed payload', async () => {
    const { client } = await connect();
    const errorQueue = client.subscribe.mock.calls
      .find(([destination]) => destination.endsWith('/queue/errors'))[1];

    errorQueue({ body: JSON.stringify({ reason: 'chat.errors.tooLong' }) });

    expect(onRefusal).toHaveBeenCalledWith({ reason: 'chat.errors.tooLong' });
  });

  it('survives a refusal frame that will not parse', async () => {
    const { client } = await connect();
    const errorQueue = client.subscribe.mock.calls
      .find(([destination]) => destination.endsWith('/queue/errors'))[1];

    expect(() => errorQueue({ body: 'not json' })).not.toThrow();
    expect(onRefusal).not.toHaveBeenCalled();
  });

  it('connects without an Authorization header when there is no token to send', async () => {
    const { client } = await connect('ada@example.com', undefined);

    // Better a refused CONNECT than a malformed "Bearer undefined".
    expect(client.connectHeaders).toEqual({});
  });

  describe('joining a board', () => {
    it('subscribes to the board chat topic and announces on it', async () => {
      const { api, client } = await connect();

      api.joinBoard(7);

      // A dot, not a slash: everything after /topic/ is one AMQP routing key.
      expect(client.subscriptions).toContain('/topic/boards.7.chat');
      expect(client.published).toContainEqual({
        destination: '/app/chat.join',
        body: JSON.stringify({ boardId: 7 }),
      });
    });

    it('drops the previous board subscription rather than stacking them up', async () => {
      const { api, client } = await connect();

      api.joinBoard(7);
      const first = client.subscribe.mock.results.at(-1).value;
      api.joinBoard(8);

      expect(first.unsubscribe).toHaveBeenCalled();
      expect(client.subscriptions).toContain('/topic/boards.8.chat');
    });

    it('does nothing without a board to join', async () => {
      const { api, client } = await connect();

      expect(api.joinBoard(null)).toBe(false);
      expect(client.subscriptions.filter((d) => d.startsWith('/topic/'))).toEqual([]);
    });

    it('unsubscribes and says so when leaving', async () => {
      const { api, client } = await connect();
      api.joinBoard(7);

      api.leaveBoard(7);

      expect(client.published).toContainEqual({
        destination: '/app/chat.leave',
        body: JSON.stringify({ boardId: 7 }),
      });
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
  });
});
