import SockJS from 'sockjs-client';
import { Client, type IMessage, type StompSubscription } from '@stomp/stompjs';
import type { Id } from '../types/api';
import { socketOrigin } from './apiOrigin';

type MessageHandler = (message: IMessage) => void;

export interface ChatRefusal {
  reason?: string;
}

export default class ChatApi {
  stompClient: Client | null = null;
  onMessageReceived: MessageHandler;
  onError: (error: unknown) => void;
  onRefusal: (refusal: ChatRefusal | null | undefined) => void;
  boardSubscription: StompSubscription | null = null;
  boardId: Id | null = null;
  serverUrl = socketOrigin();

  constructor(
    onMessageReceived: MessageHandler,
    onError: (error: unknown) => void,
    onRefusal: (refusal: ChatRefusal | null | undefined) => void
  ) {
    this.onMessageReceived = onMessageReceived;
    this.onError = onError;
    this.onRefusal = onRefusal;
  }

  connect(token?: string | null): Promise<void> {
    return new Promise((resolve, reject) => {
      try {
        const client = new Client({
          webSocketFactory: () => new SockJS(`${this.serverUrl}/ws`),
          connectHeaders: token ? { Authorization: `Bearer ${token}` } : {},
          debug: () => {},
          reconnectDelay: 5000,
          heartbeatIncoming: 4000,
          heartbeatOutgoing: 4000,
          onConnect: () => {
            client.subscribe('/user/queue/messages', this.onMessageReceived);
            client.subscribe('/user/queue/errors', (frame) => {
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
        this.stompClient = client;

        client.activate();
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

  joinBoard(boardId: Id | null | undefined) {
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

  leaveBoard(boardId: Id | null) {
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

  sendMessage(messageType: string, message: string, boardId?: Id | null, recipient?: string | null) {
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
