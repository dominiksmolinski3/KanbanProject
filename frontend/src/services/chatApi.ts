import type { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import type { Id } from '../types/api';
import { joinLiveSocket, leaveLiveSocket, type LiveSocketUser } from './liveSocket';

type MessageHandler = (message: IMessage) => void;

export interface ChatRefusal {
  reason?: string;
}

export default class ChatApi {
  stompClient: Client | null = null;
  onMessageReceived: MessageHandler;
  onError: (error: unknown) => void;
  onRefusal: (refusal: ChatRefusal | null | undefined) => void;
  onConnected?: () => void;
  queueSubscriptions: StompSubscription[] = [];
  boardSubscription: StompSubscription | null = null;
  boardId: Id | null = null;
  private socketUser: LiveSocketUser | null = null;

  constructor(
    onMessageReceived: MessageHandler,
    onError: (error: unknown) => void,
    onRefusal: (refusal: ChatRefusal | null | undefined) => void,
    onConnected?: () => void
  ) {
    this.onMessageReceived = onMessageReceived;
    this.onError = onError;
    this.onRefusal = onRefusal;
    this.onConnected = onConnected;
  }

  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.socketUser = {
        onConnect: (client) => {
          this.stompClient = client;
          this.boardSubscription = null;
          this.queueSubscriptions = [
            client.subscribe('/user/queue/messages', this.onMessageReceived),
            client.subscribe('/user/queue/errors', (frame) => {
              try {
                this.onRefusal(JSON.parse(frame.body));
              } catch {
                // A refusal that will not parse is one there is nothing useful to say about.
              }
            }),
          ];
          if (this.boardId !== null) {
            this.joinBoard(this.boardId);
          }
          this.onConnected?.();
          resolve();
        },
        onError: (error) => {
          this.onError(error);
          reject(error);
        },
      };
      this.stompClient = joinLiveSocket(this.socketUser);
    });
  }

  disconnect() {
    if (!this.socketUser) {
      return false;
    }
    if (this.boardId !== null) {
      this.leaveBoard(this.boardId);
    }
    if (this.stompClient?.connected) {
      this.queueSubscriptions.forEach((subscription) => subscription.unsubscribe());
    }
    this.queueSubscriptions = [];
    leaveLiveSocket(this.socketUser);
    this.socketUser = null;
    this.stompClient = null;
    return true;
  }

  joinBoard(boardId: Id | null | undefined) {
    if (!this.stompClient || !this.stompClient.connected || boardId === null || boardId === undefined) {
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
    if (!this.stompClient || !this.stompClient.connected) return false;

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
    if (!this.stompClient || !this.stompClient.connected || !message.trim()) return false;

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
    return Boolean(this.stompClient?.connected);
  }
}
