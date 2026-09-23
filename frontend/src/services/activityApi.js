const ACTIVITY = '/api/activity';

export const DEFAULT_PAGE_SIZE = 25;

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
