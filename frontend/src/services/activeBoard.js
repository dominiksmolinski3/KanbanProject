const ACTIVE_BOARD_KEY = 'activeBoardId';

export const getActiveBoardId = () => {
  const stored = localStorage.getItem(ACTIVE_BOARD_KEY);
  return stored ? Number(stored) : null;
};

export const setActiveBoardId = (boardId) => {
  if (boardId === null || boardId === undefined) {
    localStorage.removeItem(ACTIVE_BOARD_KEY);
  } else {
    localStorage.setItem(ACTIVE_BOARD_KEY, String(boardId));
  }
};

export const onActiveBoard = (path) => {
  const boardId = getActiveBoardId();
  if (!boardId) {
    return path;
  }
  return `${path}${path.includes('?') ? '&' : '?'}boardId=${boardId}`;
};
