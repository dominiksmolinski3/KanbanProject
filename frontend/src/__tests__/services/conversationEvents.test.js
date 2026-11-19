import { watchConversations } from '../../services/conversationEvents';
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

describe('watchConversations', () => {
  beforeEach(() => {
    clientInstances.length = 0;
  });

  test('subscribes to the account\'s conversation queue, passes frames on and says when it is live', async () => {
    const onEvent = jest.fn();
    const onLive = jest.fn();
    const stop = watchConversations(onEvent, onLive);
    const client = clientInstances[0];
    await client.completeConnect();
    const subscription = client.subscriptionTo('/user/queue/conversations');

    subscription.handler({ body: JSON.stringify({ type: 'MESSAGE', conversationId: 5, seq: 3, userId: 2 }) });
    subscription.handler({ body: 'not json' });
    client.confirm(subscription);

    expect(onEvent).toHaveBeenCalledTimes(1);
    expect(onEvent).toHaveBeenCalledWith({ type: 'MESSAGE', conversationId: 5, seq: 3, userId: 2 });
    expect(onLive).toHaveBeenCalledTimes(1);

    stop();
    expect(subscription.unsubscribe).toHaveBeenCalled();
    expect(client.deactivate).toHaveBeenCalled();
  });

  test('a reconnect subscribes again and only the newest subscription counts as live', async () => {
    const onLive = jest.fn();
    const stop = watchConversations(jest.fn(), onLive);
    const client = clientInstances[0];
    await client.completeConnect();
    const first = client.subscriptionTo('/user/queue/conversations');
    await client.completeConnect();
    const second = client.subscriptionTo('/user/queue/conversations');

    client.confirm(first);
    client.confirm(second);

    expect(onLive).toHaveBeenCalledTimes(1);
    client.connected = false;
    stop();
    expect(second.unsubscribe).not.toHaveBeenCalled();
  });
});
