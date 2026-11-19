import type { StompSubscription } from '@stomp/stompjs';
import { confirmedBy, joinLiveSocket, leaveLiveSocket, type LiveSocketUser } from './liveSocket';

export type ConversationEventType = 'MESSAGE' | 'COMMIT' | 'WELCOME' | 'MEMBERS' | 'READ' | 'TYPING';

export interface ConversationEvent {
  type: ConversationEventType;
  conversationId: number | null;
  seq: number | null;
  userId: number | null;
}

export function watchConversations(onEvent: (event: ConversationEvent) => void, onLive: () => void): () => void {
  let subscription: StompSubscription | null = null;
  let connected: { connected: boolean } | null = null;
  const user: LiveSocketUser = {
    onConnect: (client) => {
      connected = client;
      const current = client.subscribe('/user/queue/conversations', (frame) => {
        try {
          onEvent(JSON.parse(frame.body));
        } catch {
          return;
        }
      }, confirmedBy(client, () => {
        if (subscription === current) onLive();
      }));
      subscription = current;
    },
  };
  joinLiveSocket(user);
  return () => {
    if (connected?.connected) subscription?.unsubscribe();
    subscription = null;
    leaveLiveSocket(user);
  };
}
