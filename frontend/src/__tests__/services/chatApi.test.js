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
  beforeEach(() => {
    clientInstances.length = 0;
    jest.clearAllMocks();
  });

  const connect = async (username = 'ada@example.com', ...token) => {
    const api = new ChatApi(jest.fn(), jest.fn());
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
    expect(client.subscriptions).toContain('/topic/public');
  });

  it('announces the user on the public topic once connected', async () => {
    const { client } = await connect('ada@example.com');

    expect(client.published).toContainEqual({
      destination: '/app/chat.addUser',
      body: JSON.stringify({ sender: 'ada@example.com', type: 'JOIN' }),
    });
  });

  it('connects without an Authorization header when there is no token to send', async () => {
    const { client } = await connect('ada@example.com', undefined);

    // Better a refused CONNECT than a malformed "Bearer undefined".
    expect(client.connectHeaders).toEqual({});
  });
});
