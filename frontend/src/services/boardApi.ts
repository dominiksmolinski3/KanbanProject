import { deleteChecked, deleteJson, getJson, patchJson, postJson, readOk } from './http';
import type { Board, BoardInvitation, BoardRole, Id } from '../types/api';

const BOARDS = '/api/boards';

export const fetchBoards = () => getJson<Board[]>(BOARDS);

export const fetchCurrentBoard = () => getJson<Board>(`${BOARDS}/current`);

export const createBoard = (name: string) => postJson<Board>(BOARDS, { name });

export const renameBoard = (boardId: Id, name: string) => patchJson<Board>(`${BOARDS}/${boardId}`, { name });

export const deleteBoard = (boardId: Id) => deleteChecked(`${BOARDS}/${boardId}`);

export const inviteToBoard = (boardId: Id, email: string, role?: BoardRole) =>
  postJson<BoardInvitation>(`${BOARDS}/${boardId}/invitations`, role ? { email, role } : { email });

export const fetchBoardInvitations = (boardId: Id) => getJson<BoardInvitation[]>(`${BOARDS}/${boardId}/invitations`);

export const revokeBoardInvitation = (boardId: Id, invitationId: Id) =>
  deleteChecked(`${BOARDS}/${boardId}/invitations/${invitationId}`);

const INVITATIONS = '/api/invitations';

export const fetchMyInvitations = () => getJson<BoardInvitation[]>(INVITATIONS);

export const acceptInvitation = (invitationId: Id) => postJson<Board>(`${INVITATIONS}/${invitationId}/accept`);

export const declineInvitation = async (invitationId: Id) =>
  readOk(await fetch(`${INVITATIONS}/${invitationId}/decline`, { method: 'POST' }));

export const removeBoardMember = (boardId: Id, userId: Id) => deleteJson<Board>(`${BOARDS}/${boardId}/members/${userId}`);
