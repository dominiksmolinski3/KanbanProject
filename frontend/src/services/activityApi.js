// What has happened on a board.
//
// One route, and it is paged. The board listing deliberately is not - a board renders every card
// it has and is bounded by what a team will put on one - but a feed is bounded by nothing: it only
// grows, and a board a year old would answer a year of entries to draw twenty of them.
const ACTIVITY = '/api/activity';

export const DEFAULT_PAGE_SIZE = 25;

/**
 * One page of the feed, newest first.
 *
 * `boardId` may be left out, which means the caller's own board - the same convention every other
 * listing here follows. Asking for a page size above the server's maximum is a 400 rather than a
 * quiet clamp, so this does not try to be clever about the number it sends.
 */
export const fetchActivity = async ({ boardId, page = 0, size = DEFAULT_PAGE_SIZE } = {}) => {
  const params = new URLSearchParams();
  if (boardId !== undefined && boardId !== null) {
    params.set('boardId', String(boardId));
  }
  params.set('page', String(page));
  params.set('size', String(size));

  const response = await fetch(`${ACTIVITY}?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Error fetching the activity feed: ${response.status}`);
  }
  return response.json();
};
