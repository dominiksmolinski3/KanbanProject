import { createContext, useRef } from 'react';
import type { KanbanValue } from '../KanbanContext';

const CARD_ACTIONS = [
  'deleteTask',
  'refreshTasks',
  'updateTaskName',
  'updateTaskCompletion',
  'setDailyFocus',
  'setDraggedTaskId',
  'dragAndDrop',
  'keyboardMove',
] as const;

export type CardActions = Pick<KanbanValue, (typeof CARD_ACTIONS)[number]>;

export const CardActionsContext = createContext<CardActions | null>(null);

type Bag = Record<string, unknown>;

function liveView(read: () => Bag, keys: readonly string[] = Object.keys(read())): Bag {
  const view: Bag = {};
  for (const key of keys) {
    const value = read()[key];
    if (typeof value === 'function') {
      view[key] = (...args: unknown[]) => (read()[key] as (...all: unknown[]) => unknown)(...args);
    } else if (value !== null && typeof value === 'object') {
      view[key] = liveView(() => read()[key] as Bag);
    } else {
      Object.defineProperty(view, key, { get: () => read()[key], enumerable: true });
    }
  }
  return view;
}

// The object and its functions never change identity, so a memoised card is not re-rendered by a new closure.
export function useCardActions(latest: CardActions): CardActions {
  const current = useRef(latest);
  current.current = latest;
  const stable = useRef<CardActions | null>(null);
  if (!stable.current) {
    stable.current = liveView(() => current.current as unknown as Bag, CARD_ACTIONS) as unknown as CardActions;
  }
  return stable.current;
}
