// Boards and their members.
//
// A board is the unit of tenancy: every column, swimlane and task belongs to exactly one, and
// being on its member list is the only thing that grants access to any of them. The routes below
// are the whole of what a client can do to that list.
const BOARDS = '/api/boards';

const read = async (response, what) => {
  if (!response.ok) {
    throw new Error(`Error ${what}: ${response.status}`);
  }
  return response.json();
};

/** Every board the caller owns or has been invited to, oldest first. */
export const fetchBoards = async () => {
  const response = await fetch(BOARDS);
  return read(response, 'fetching boards');
};

/**
 * The board the listings answer with when they are not told which one, created if the account has
 * none. Asked for once on load so the client knows what it is looking at rather than guessing.
 */
export const fetchCurrentBoard = async () => {
  const response = await fetch(`${BOARDS}/current`);
  return read(response, 'fetching the current board');
};

export const createBoard = async (name) => {
  const response = await fetch(BOARDS, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name })
  });
  return read(response, 'creating a board');
};

export const renameBoard = async (boardId, name) => {
  const response = await fetch(`${BOARDS}/${boardId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name })
  });
  return read(response, 'renaming the board');
};

export const deleteBoard = async (boardId) => {
  const response = await fetch(`${BOARDS}/${boardId}`, { method: 'DELETE' });
  if (!response.ok) {
    throw new Error(`Error deleting the board: ${response.status}`);
  }
  return true;
};

/**
 * Adds the account that signed up with this address, if there is one.
 *
 * <p>An address with no account is not an error and changes nothing — the server answers the same
 * either way, so this call cannot be used to test which addresses have accounts here. The caller
 * gets the board back and can see for itself who is on it.
 */
export const addBoardMember = async (boardId, email) => {
  const response = await fetch(`${BOARDS}/${boardId}/members`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email })
  });
  return read(response, 'adding a member');
};

/** The owner removing somebody, or a member removing themselves. The owner cannot be removed. */
export const removeBoardMember = async (boardId, userId) => {
  const response = await fetch(`${BOARDS}/${boardId}/members/${userId}`, { method: 'DELETE' });
  return read(response, 'removing a member');
};
