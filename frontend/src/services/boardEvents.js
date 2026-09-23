import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { getAccessToken, isAccessTokenExpired, refreshSession } from './session';

export default class BoardEvents {
  constructor() {
    this.client = null;
    this.subscription = null;
    this.boardId = null;
    this.onEvent = null;
    this.serverUrl = typeof window !== 'undefined' ? window.location.origin : '';
  }

  watch(boardId, onEvent) {
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

      beforeConnect: async () => {
        if (isAccessTokenExpired()) {
          try {
            await refreshSession();
          } catch {
            // The interceptor owns signing somebody out; refusing to connect is all this can do.
          }
        }
        const token = getAccessToken();
        this.client.connectHeaders = token ? { Authorization: `Bearer ${token}` } : {};
      },

      onConnect: () => this.resubscribe(),

      onStompError: () => {},
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
