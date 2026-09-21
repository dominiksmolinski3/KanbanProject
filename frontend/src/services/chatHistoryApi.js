// Scroll-back.
//
// Until the server grew these routes the panel showed only what arrived while it was open: every
// message was written to a table nothing read, so closing the panel lost the conversation and the
// rows stayed in the database forever regardless. Paged exactly like the activity feed, newest
// first, because a conversation is bounded by nothing either.
const CHAT = '/api/chat';
const CHAT_DIRECT = '/api/chat/direct';

export const DEFAULT_PAGE_SIZE = 25;

/*
 * Both routes are fetched literally rather than through one helper taking the URL. A helper would
 * be shorter and would cost `ClientRoutesExistTest` its subject: that guard resolves every fetch
 * URL in this directory back to a @RestController, and a URL arriving as an argument resolves to
 * nothing it can check. It has already caught this exact shape once.
 */

/**
 * One page of a board's conversation.
 *
 * `boardId` may be left out, which means the caller's own board - the same convention every other
 * listing here follows. Asking for a size above the server's maximum is a 400 rather than a quiet
 * clamp, so this does not try to be clever about the number it sends.
 */
export const fetchBoardChatHistory = async ({ boardId, page: pageNumber = 0, size = DEFAULT_PAGE_SIZE } = {}) => {
  const params = new URLSearchParams();
  if (boardId !== undefined && boardId !== null) {
    params.set('boardId', String(boardId));
  }
  params.set('page', String(pageNumber));
  params.set('size', String(size));

  const response = await fetch(`${CHAT}?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Error fetching the board conversation: ${response.status}`);
  }
  return response.json();
};

/**
 * One page of the thread with one peer, in both directions.
 *
 * An account that shares no board with the caller is a 404, the same answer an address with no
 * account here gets - which is the peer scoping `GET /api/users` enforces, kept on this route too.
 */
export const fetchDirectChatHistory = async ({ peer, page: pageNumber = 0, size = DEFAULT_PAGE_SIZE }) => {
  const params = new URLSearchParams();
  params.set('with', peer);
  params.set('page', String(pageNumber));
  params.set('size', String(size));

  const response = await fetch(`${CHAT_DIRECT}?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Error fetching the direct thread: ${response.status}`);
  }
  return response.json();
};
