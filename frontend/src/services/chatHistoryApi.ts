import { getJson, queryString } from './http';
import type { ChatMessageResults, Id, PageRequest } from '../types/api';

const CHAT = '/api/chat';
const CHAT_DIRECT = '/api/chat/direct';

export const DEFAULT_PAGE_SIZE = 25;

export const fetchBoardChatHistory = (
  { boardId, page: pageNumber = 0, size = DEFAULT_PAGE_SIZE }: PageRequest & { boardId?: Id | null } = {}
) => getJson<ChatMessageResults>(`${CHAT}?${queryString({ boardId, page: pageNumber, size })}`);

export const fetchDirectChatHistory = (
  { peer, page: pageNumber = 0, size = DEFAULT_PAGE_SIZE }: PageRequest & { peer: string }
) => getJson<ChatMessageResults>(`${CHAT_DIRECT}?${queryString({ with: peer, page: pageNumber, size })}`);
