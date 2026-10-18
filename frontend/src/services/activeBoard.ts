import type { Id } from '../types/api';

const ACTIVE_BOARD_KEY = 'activeBoardId';

export const getActiveBoardId = (): Id | null => {
  const stored = localStorage.getItem(ACTIVE_BOARD_KEY);
  return stored ? Number(stored) : null;
};

export const setActiveBoardId = (boardId: Id | null | undefined): void => {
  if (boardId === null || boardId === undefined) {
    localStorage.removeItem(ACTIVE_BOARD_KEY);
  } else {
    localStorage.setItem(ACTIVE_BOARD_KEY, String(boardId));
  }
};

export const onActiveBoard = (path: string): string => {
  const boardId = getActiveBoardId();
  if (!boardId) {
    return path;
  }
  return `${path}${path.includes('?') ? '&' : '?'}boardId=${boardId}`;
};
