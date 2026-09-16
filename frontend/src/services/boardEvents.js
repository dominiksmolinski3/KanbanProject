import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { getAccessToken, isAccessTokenExpired, refreshSession } from './session';

/**
 * The board's live connection: one STOMP subscription that says "this board changed, re-read it".
 * Deliberately not the connection `chatApi` holds, since that one opens and closes with the chat
 * panel and a board riding on it would stop updating whenever anyone closed the panel. What
 * arrives is `{ type, boardId }` only - the re-read goes back through the REST routes, which are
 * what decide what this account may see.
 */
export default class BoardEvents {
  constructor() {
    this.client = null;
    this.subscription = null;
    this.boardId = null;
    this.onEvent = null;
    this.serverUrl = typeof window !== 'undefined' ? window.location.origin : '';
  }

  /**
   * Watches one board, replacing whatever was being watched before.
   *
   * Safe to call on every board switch: the connection is kept and only the subscription moves,
   * because tearing down SockJS to change a destination costs a handshake for nothing.
   */
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

      /*
       * The CONNECT frame is the only place the fifteen-minute access token is checked, so a
       * client reconnecting later (a laptop that slept, a dropped idle socket) would otherwise
       * retry with the same dead token forever. `beforeConnect` runs on every attempt, including
       * retries, so it can refresh first and hold a live token.
       */
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

      /*
       * A refused SUBSCRIBE arrives here as a STOMP ERROR frame rather than an exception at the
       * call site. Nothing useful to tell the person - a refusal here means the board stopped
       * being theirs while they watched, and the next re-read will say so.
       */
      onStompError: () => {},
      onWebSocketError: () => {},
    });

    this.client.activate();
  }

  /** Points the single subscription at the current board. Idempotent, and safe before connecting. */
  resubscribe() {
    if (this.subscription) {
      this.subscription.unsubscribe();
      this.subscription = null;
    }
    if (!this.client || !this.client.connected || this.boardId == null) {
      return;
    }
    this.subscription = this.client.subscribe(`/topic/boards/${this.boardId}`, (frame) => {
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
