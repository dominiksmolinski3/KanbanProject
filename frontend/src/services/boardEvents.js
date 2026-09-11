import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';
import { getAccessToken, isAccessTokenExpired, refreshSession } from './session';

/**
 * The board's live connection: one STOMP subscription that says "this board changed, re-read it".
 *
 * Deliberately **not** the connection `chatApi` holds. That one is opened when somebody opens the
 * chat panel and closed when they close it, so a board riding on it would stop updating the moment
 * anyone tidied their screen - and would never start for the people who never open chat at all.
 * Two connections is the cost of the two features having genuinely different lifetimes.
 *
 * What arrives is `{ type, boardId }` and nothing else. The re-read goes back through the REST
 * routes, which are what decide what this account may see; see `BoardEvent` on the server for why
 * the frame carries no task in it.
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
       * The access token is good for fifteen minutes and the CONNECT frame is the only place it is
       * ever checked. A client that reconnects an hour later - a laptop that slept, a proxy that
       * dropped an idle socket - would otherwise present the token it was constructed with, be
       * refused, and retry with the same dead token every five seconds forever. `beforeConnect`
       * runs on every attempt, including the retries, which is the only hook that can hold a live
       * token.
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
       * A refused SUBSCRIBE - somebody else's board - arrives here rather than as an exception at
       * the call site, because STOMP answers a frame with an ERROR frame. There is nothing useful
       * to tell the person: they are looking at a board they can see over HTTP, so a refusal here
       * means it stopped being theirs while they watched, and the next re-read will say so.
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
