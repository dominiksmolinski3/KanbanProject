import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

/**
 * The chat panel's own STOMP connection.
 *
 * It used to subscribe to `/topic/public` on connect - one global room every signed-in account
 * was on, where a member of one board read the messages of every other board's members - and to
 * rooms named by free text the client chose. Neither is addressable any more: a message belongs to
 * a board, it travels `/topic/boards.{id}.chat`, and `BoardSubscriptionInterceptor` refuses that
 * subscription for anybody the board is not visible to. **A dot, not a slash**: everything after
 * `/topic/` is one AMQP routing key, and RabbitMQ refuses a destination containing a further `/`.
 *
 * Two user destinations, not one. `/queue/messages` is where a direct message arrives;
 * `/queue/errors` is where the server says one of *your own* messages was not sent, which it can
 * now do without throwing - a throw on the inbound channel closes the session, so pasting
 * something too long used to drop the connection rather than bounce the message.
 */
export default class ChatApi {
  constructor(onMessageReceived, onError, onRefusal) {
    this.stompClient = null;
    this.onMessageReceived = onMessageReceived;
    this.onError = onError;
    this.onRefusal = onRefusal;
    this.boardSubscription = null;
    this.boardId = null;
    this.serverUrl = typeof window !== 'undefined' ? window.location.origin : '';
  }

  /**
   * @param {string} username the authenticated principal — the account's email. Spring resolves
   *   /user/{name}/queue destinations against it, so anything else subscribes to a queue that
   *   never receives a message.
   * @param {string} token the JWT. WebSocketAuthInterceptor refuses a CONNECT frame without it.
   */
  connect(username, token) {
    return new Promise((resolve, reject) => {
      try {
        this.stompClient = new Client({
          webSocketFactory: () => new SockJS(`${this.serverUrl}/ws`),
          connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
          debug: () => {},
          reconnectDelay: 5000,
          heartbeatIncoming: 4000,
          heartbeatOutgoing: 4000,
          onConnect: () => {
            this.stompClient.subscribe(`/user/${username}/queue/messages`, this.onMessageReceived);
            this.stompClient.subscribe(`/user/${username}/queue/errors`, (frame) => {
              try {
                this.onRefusal(JSON.parse(frame.body));
              } catch {
                // A refusal that will not parse is one there is nothing useful to say about.
              }
            });
            resolve();
          },
          onStompError: (frame) => {
            this.onError(new Error('STOMP protocol error'));
            reject(frame);
          },
          onWebSocketError: (error) => {
            this.onError(error);
            reject(error);
          }
        });

        this.stompClient.activate();
      } catch (error) {
        this.onError(error);
        reject(error);
      }
    });
  }

  disconnect() {
    if (this.boardId !== null) {
      this.leaveBoard(this.boardId);
    }

    if (this.stompClient && this.stompClient.active) {
      this.stompClient.deactivate();
      return true;
    }
    return false;
  }

  /**
   * Listens to one board, replacing whatever was being listened to before - the board switcher
   * calls this on every switch, and tearing the socket down to move a destination would cost a
   * handshake for nothing.
   *
   * A subscription the caller may not have is dropped by the server rather than refused, so this
   * returning true means the frame was sent, not that anything will arrive on it.
   */
  joinBoard(boardId) {
    if (!this.stompClient || !this.stompClient.active || boardId === null || boardId === undefined) {
      return false;
    }
    if (this.boardSubscription) {
      this.boardSubscription.unsubscribe();
      this.boardSubscription = null;
    }

    this.boardId = boardId;
    this.boardSubscription = this.stompClient.subscribe(
      `/topic/boards.${boardId}.chat`, this.onMessageReceived);
    this.stompClient.publish({
      destination: '/app/chat.join',
      body: JSON.stringify({ boardId })
    });
    return true;
  }

  leaveBoard(boardId) {
    if (!this.stompClient || !this.stompClient.active) return false;

    if (this.boardSubscription) {
      this.boardSubscription.unsubscribe();
      this.boardSubscription = null;
    }
    this.boardId = null;
    this.stompClient.publish({
      destination: '/app/chat.leave',
      body: JSON.stringify({ boardId })
    });
    return true;
  }

  /**
   * Sends one message. `sender`, the type and the timestamp are stamped by the server over
   * whatever is put here, so only the content, the board and the recipient carry anything.
   */
  sendMessage(messageType, message, boardId, recipient) {
    if (!this.stompClient || !this.stompClient.active || !message.trim()) return false;

    if (messageType === 'private') {
      if (!recipient || !recipient.trim()) return false;

      this.stompClient.publish({
        destination: '/app/chat.sendPrivateMessage',
        body: JSON.stringify({ content: message, recipientId: recipient })
      });
      return true;
    }

    if (boardId === null || boardId === undefined) return false;

    this.stompClient.publish({
      destination: '/app/chat.sendMessage',
      body: JSON.stringify({ content: message, boardId })
    });
    return true;
  }

  isConnected() {
    return this.stompClient && this.stompClient.active;
  }
}
