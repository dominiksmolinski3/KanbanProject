import { apiError } from './apiError';

const BOARDS = '/api/boards';

const read = async (response) => {
  if (!response.ok) {
    throw await apiError(response);
  }
  return response.json();
};

export const fetchBoards = async () => {
  const response = await fetch(BOARDS);
  return read(response);
};

export const fetchCurrentBoard = async () => {
  const response = await fetch(`${BOARDS}/current`);
  return read(response);
};

export const createBoard = async (name) => {
  const response = await fetch(BOARDS, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name })
  });
  return read(response);
};

export const renameBoard = async (boardId, name) => {
  const response = await fetch(`${BOARDS}/${boardId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name })
  });
  return read(response);
};

export const deleteBoard = async (boardId) => {
  const response = await fetch(`${BOARDS}/${boardId}`, { method: 'DELETE' });
  if (!response.ok) {
    throw await apiError(response);
  }
  return true;
};

export const inviteToBoard = async (boardId, email, role) => {
  const response = await fetch(`${BOARDS}/${boardId}/invitations`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(role ? { email, role } : { email })
  });
  return read(response);
};

export const fetchBoardInvitations = async (boardId) => {
  const response = await fetch(`${BOARDS}/${boardId}/invitations`);
  return read(response);
};

export const revokeBoardInvitation = async (boardId, invitationId) => {
  const response = await fetch(`${BOARDS}/${boardId}/invitations/${invitationId}`, {
    method: 'DELETE'
  });
  if (!response.ok) {
    throw await apiError(response);
  }
  return true;
};

const INVITATIONS = '/api/invitations';

export const fetchMyInvitations = async () => {
  const response = await fetch(INVITATIONS);
  return read(response);
};

export const acceptInvitation = async (invitationId) => {
  const response = await fetch(`${INVITATIONS}/${invitationId}/accept`, { method: 'POST' });
  return read(response);
};

export const declineInvitation = async (invitationId) => {
  const response = await fetch(`${INVITATIONS}/${invitationId}/decline`, { method: 'POST' });
  if (!response.ok) {
    throw await apiError(response);
  }
  return true;
};

export const removeBoardMember = async (boardId, userId) => {
  const response = await fetch(`${BOARDS}/${boardId}/members/${userId}`, { method: 'DELETE' });
  return read(response);
};
