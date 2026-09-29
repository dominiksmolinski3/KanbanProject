import { apiError } from './apiError';

const CHAT = '/api/chat';
const CHAT_DIRECT = '/api/chat/direct';

export const DEFAULT_PAGE_SIZE = 25;

export const fetchBoardChatHistory = async ({ boardId, page: pageNumber = 0, size = DEFAULT_PAGE_SIZE } = {}) => {
  const params = new URLSearchParams();
  if (boardId !== undefined && boardId !== null) {
    params.set('boardId', String(boardId));
  }
  params.set('page', String(pageNumber));
  params.set('size', String(size));

  const response = await fetch(`${CHAT}?${params.toString()}`);
  if (!response.ok) {
    throw await apiError(response);
  }
  return response.json();
};

export const fetchDirectChatHistory = async ({ peer, page: pageNumber = 0, size = DEFAULT_PAGE_SIZE }) => {
  const params = new URLSearchParams();
  params.set('with', peer);
  params.set('page', String(pageNumber));
  params.set('size', String(size));

  const response = await fetch(`${CHAT_DIRECT}?${params.toString()}`);
  if (!response.ok) {
    throw await apiError(response);
  }
  return response.json();
};
