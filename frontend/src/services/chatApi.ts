import type { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import type { Id } from '../types/api';
import { confirmedBy, joinLiveSocket, leaveLiveSocket, type LiveSocketUser } from './liveSocket';

type MessageHandler = (message: IMessage) => void;

export interface ChatRefusal {
  reason?: string;
}

export default class ChatApi {
  stompClient: Client | null = null;
  onMessageReceived: MessageHandler;
  onError: (error: unknown) => void;
  onRefusal: (refusal: ChatRefusal | null | undefined) => void;
  onReady?: (ready: boolean) => void;
  queueSubscriptions: StompSubscription[] = [];
  boardSubscription: StompSubscription | null = null;
  boardId: Id | null = null;
  private socketUser: LiveSocketUser | null = null;
  private queuesLive = false;
  private boardLive = false;
  private ready = false;

  constructor(
    onMessageReceived: MessageHandler,
    onError: (error: unknown) => void,
    onRefusal: (refusal: ChatRefusal | null | undefined) => void,
    onReady?: (ready: boolean) => void
  ) {
    this.onMessageReceived = onMessageReceived;
    this.onError = onError;
    this.onRefusal = onRefusal;
    this.onReady = onReady;
  }

  connect(): Promise<void> {
    return new Promise((resolve, reject) => {
      this.socketUser = {
        onConnect: (client) => {
          this.stompClient = client;
          this.boardSubscription = null;
          this.queuesLive = false;
          this.boardLive = false;
          this.announce();
          const messages = client.subscribe('/user/queue/messages', this.onMessageReceived, confirmedBy(client, () => {
            if (this.queueSubscriptions.includes(messages)) {
              this.queuesLive = true;
              this.announce();
            }
          }));
          this.queueSubscriptions = [
            messages,
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

  private announce() {
    const ready = this.queuesLive && (this.boardId === null || this.boardLive);
    if (ready !== this.ready) {
      this.ready = ready;
      this.onReady?.(ready);
    }
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
    this.queuesLive = false;
    this.ready = false;
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
    this.boardLive = false;
    this.announce();
    const client = this.stompClient;
    const conversation = client.subscribe(
      `/topic/boards.${boardId}.chat`, this.onMessageReceived, confirmedBy(client, () => {
        if (this.boardSubscription !== conversation) {
          return;
        }
        client.publish({
          destination: '/app/chat.join',
          body: JSON.stringify({ boardId })
        });
        this.boardLive = true;
        this.announce();
      }));
    this.boardSubscription = conversation;
    return true;
  }

  leaveBoard(boardId: Id | null) {
    if (!this.stompClient || !this.stompClient.connected) return false;

    if (this.boardSubscription) {
      this.boardSubscription.unsubscribe();
      this.boardSubscription = null;
    }
    this.boardId = null;
    this.boardLive = false;
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
