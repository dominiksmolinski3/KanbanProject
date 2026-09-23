import SockJS from 'sockjs-client';
import { Client } from '@stomp/stompjs';

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

  connect(token) {
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
            this.stompClient.subscribe('/user/queue/messages', this.onMessageReceived);
            this.stompClient.subscribe('/user/queue/errors', (frame) => {
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
