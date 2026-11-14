import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { getAccessToken, isAccessTokenExpired, refreshSession } from './session';
import { renewOrSignOut } from './apiInterceptor';
import { socketOrigin } from './apiOrigin';

export interface LiveSocketUser {
  // A connection numbers its subscriptions from zero again, so drop the old handles here; never unsubscribe them.
  onConnect: (client: Client) => void;
  onError?: (error: unknown) => void;
}

let client: Client | null = null;
const users = new Set<LiveSocketUser>();

export function joinLiveSocket(user: LiveSocketUser): Client {
  users.add(user);
  if (client) {
    if (client.connected) {
      user.onConnect(client);
    }
    return client;
  }

  const created = new Client({
    webSocketFactory: () => new SockJS(`${socketOrigin()}/ws`),
    debug: () => {},
    reconnectDelay: 5000,
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,

    beforeConnect: async () => {
      if (isAccessTokenExpired()) {
        try {
          await refreshSession();
        } catch {
          // The interceptor owns signing somebody out; refusing to connect is all this can do.
        }
      }
      const token = getAccessToken();
      created.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
    },

    onConnect: () => {
      [...users].forEach((each) => each.onConnect(created));
    },

    onStompError: () => {
      [...users].forEach((each) => each.onError?.(new Error('STOMP protocol error')));
      renewOrSignOut().catch(() => {});
    },

    onWebSocketError: (error) => {
      [...users].forEach((each) => each.onError?.(error));
    },
  });
  client = created;
  created.activate();
  return created;
}

export function leaveLiveSocket(user: LiveSocketUser) {
  if (!users.delete(user) || users.size > 0 || !client) {
    return;
  }
  client.deactivate();
  client = null;
}
