import SockJS from 'sockjs-client';
import { Client, type StompSubscription } from '@stomp/stompjs';
import { getAccessToken, isAccessTokenExpired, refreshSession } from './session';
import { renewOrSignOut } from './apiInterceptor';
import type { Id } from '../types/api';
import { socketOrigin } from './apiOrigin';

export interface BoardEvent {
  type: string;
  boardId: Id;
}

type BoardEventHandler = (event: BoardEvent) => void;

export default class BoardEvents {
  client: Client | null = null;
  subscription: StompSubscription | null = null;
  boardId: Id | null = null;
  onEvent: BoardEventHandler | null = null;
  serverUrl = socketOrigin();

  watch(boardId: Id, onEvent: BoardEventHandler) {
    this.onEvent = onEvent;
    this.boardId = boardId;

    if (this.client) {
      this.resubscribe();
      return;
    }

    this.client = new Client({
      webSocketFactory: () => new SockJS(`${this.serverUrl}/ws`),
      debug: () => {},
      reconnectDelay: 5000,
      heartbeatIncoming: 10000,
      heartbeatOutgoing: 10000,

      beforeConnect: async (client) => {
        if (isAccessTokenExpired()) {
          try {
            await refreshSession();
          } catch {
            // The interceptor owns signing somebody out; refusing to connect is all this can do.
          }
        }
        const token = getAccessToken();
        client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
      },

      onConnect: () => this.resubscribe(),

      onStompError: () => {
        renewOrSignOut().catch(() => {});
      },
      onWebSocketError: () => {},
    });

    this.client.activate();
  }

  resubscribe() {
    if (this.subscription) {
      this.subscription.unsubscribe();
      this.subscription = null;
    }
    if (!this.client || !this.client.connected || this.boardId == null) {
      return;
    }
    this.subscription = this.client.subscribe(`/topic/boards.${this.boardId}`, (frame) => {
      if (!this.onEvent) {
        return;
      }
      try {
        this.onEvent(JSON.parse(frame.body));
      } catch {
        // A frame this client cannot read is a frame it cannot act on; the board is still correct.
      }
    });
  }

  stop() {
    if (this.subscription) {
      this.subscription.unsubscribe();
      this.subscription = null;
    }
    if (this.client) {
      this.client.deactivate();
      this.client = null;
    }
    this.onEvent = null;
    this.boardId = null;
  }
}
