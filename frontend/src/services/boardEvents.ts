import type { Client, StompSubscription } from '@stomp/stompjs';
import type { Id } from '../types/api';
import { joinLiveSocket, leaveLiveSocket, type LiveSocketUser } from './liveSocket';

export interface BoardEvent {
  type: string;
  boardId: Id;
}

type BoardEventHandler = (event: BoardEvent) => void;

export default class BoardEvents {
  client: Client | null = null;
  subscription: StompSubscription | null = null;
  boardsSubscription: StompSubscription | null = null;
  onBoardsChanged: (() => void) | null = null;
  boardId: Id | null = null;
  onEvent: BoardEventHandler | null = null;

  private readonly socketUser: LiveSocketUser = {
    onConnect: (client) => {
      this.client = client;
      this.subscription = null;
      this.boardsSubscription = null;
      this.subscribe();
    },
  };

  watch(boardId: Id, onEvent: BoardEventHandler, onBoardsChanged?: () => void) {
    this.onEvent = onEvent;
    this.onBoardsChanged = onBoardsChanged ?? null;
    this.boardId = boardId;

    if (this.client) {
      this.unsubscribe();
      this.subscribe();
      return;
    }

    this.client = joinLiveSocket(this.socketUser);
  }

  private subscribe() {
    if (!this.client || !this.client.connected || this.boardId == null) {
      return;
    }
    this.boardsSubscription = this.client.subscribe('/user/queue/boards', () => {
      this.onBoardsChanged?.();
    });
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

  private unsubscribe() {
    if (this.client?.connected) {
      this.subscription?.unsubscribe();
      this.boardsSubscription?.unsubscribe();
    }
    this.subscription = null;
    this.boardsSubscription = null;
  }

  stop() {
    this.unsubscribe();
    this.onBoardsChanged = null;
    if (this.client) {
      leaveLiveSocket(this.socketUser);
      this.client = null;
    }
    this.onEvent = null;
    this.boardId = null;
  }
}
