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
 * Invites the person at this address to the board.
 *
 * Nothing happens to the member list here: the invitation is an offer, and the person it names
 * has to accept it. The answer is the invitation and never the board, which is the fix for the
 * route this replaced - that one answered with the member list, so an owner could diff it and
 * learn whether an address had an account here.
 */
export const inviteToBoard = async (boardId, email) => {
  const response = await fetch(`${BOARDS}/${boardId}/invitations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email })
  });
  return read(response, 'sending an invitation');
};

/** What is still outstanding on a board the caller owns. */
export const fetchBoardInvitations = async (boardId) => {
  const response = await fetch(`${BOARDS}/${boardId}/invitations`);
  return read(response, 'fetching invitations');
};

/** The owner's second thought. A revoked invitation cannot be accepted afterwards. */
export const revokeBoardInvitation = async (boardId, invitationId) => {
  const response = await fetch(`${BOARDS}/${boardId}/invitations/${invitationId}`, {
    method: 'DELETE'
  });
  if (!response.ok) {
    throw new Error(`Error revoking the invitation: ${response.status}`);
  }
  return true;
};

/*
 * The invitee's own three routes. They are not under /boards, because the caller cannot see the
 * board an invitation names - that is what the invitation is for - so a board-scoped path would
 * have to 404 on the only case it exists to serve.
 */
const INVITATIONS = '/api/invitations';

/** Every board the caller has been asked to join, matched on their account's address. */
export const fetchMyInvitations = async () => {
  const response = await fetch(INVITATIONS);
  return read(response, 'fetching your invitations');
};

/** Answers with the board, which the caller can see now and could not a moment ago. */
export const acceptInvitation = async (invitationId) => {
  const response = await fetch(`${INVITATIONS}/${invitationId}/accept`, { method: 'POST' });
  return read(response, 'accepting the invitation');
};

export const declineInvitation = async (invitationId) => {
  const response = await fetch(`${INVITATIONS}/${invitationId}/decline`, { method: 'POST' });
  if (!response.ok) {
    throw new Error(`Error declining the invitation: ${response.status}`);
  }
  return true;
};

/** The owner removing somebody, or a member removing themselves. The owner cannot be removed. */
export const removeBoardMember = async (boardId, userId) => {
  const response = await fetch(`${BOARDS}/${boardId}/members/${userId}`, { method: 'DELETE' });
  return read(response, 'removing a member');
};
