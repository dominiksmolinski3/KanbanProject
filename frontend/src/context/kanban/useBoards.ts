import { useEffect, useState } from 'react';
import { toast } from 'react-toastify';
import { getActiveBoardId, setActiveBoardId as persistActiveBoardId } from '../../services/api';
import {
  fetchBoards,
  fetchCurrentBoard,
  createBoard,
  renameBoard,
  deleteBoard,
  inviteToBoard,
  revokeBoardInvitation,
  fetchMyInvitations,
  acceptInvitation,
  declineInvitation,
  removeBoardMember,
} from '../../services/boardApi';
import type { Board, BoardInvitation, BoardRole, Id } from '../../types/api';
import { messageOf, reportError } from './reportError';
import type { SetState, Translate } from './types';

interface BoardsDeps {
  t: Translate;
  setError: SetState<string | null>;
  setLoading: SetState<boolean>;
}

export function useBoards({ t, setError, setLoading }: BoardsDeps) {
  const [boards, setBoards] = useState<Board[]>([]);
  const [activeBoardId, setActiveBoardId] = useState<Id | null>(null);
  const [myInvitations, setMyInvitations] = useState<BoardInvitation[]>([]);

  const refreshMyInvitations = async () => {
    try {
      const mine = await fetchMyInvitations();
      setMyInvitations(mine);
      return mine;
    } catch (err) {
      console.error('Error fetching invitations:', err);
      return [];
    }
  };

  useEffect(() => {
    const resolveBoard = async () => {
      try {
        const [available, current] = await Promise.all([fetchBoards(), fetchCurrentBoard()]);
        setBoards(available);

        const remembered = getActiveBoardId();
        const chosen = available.some(board => board.id === remembered) ? remembered : current.id;
        persistActiveBoardId(chosen);
        setActiveBoardId(chosen);
      } catch (err) {
        console.error('Error resolving the board:', err);
        setError(messageOf(err));
        setLoading(false);
      }
    };

    resolveBoard();
    refreshMyInvitations();
  }, [setError, setLoading]);

  const activeBoard = boards.find(board => board.id === activeBoardId) || null;
  const isViewer = activeBoard?.role === 'VIEWER';

  const selectBoard = (boardId: Id) => {
    if (boardId === activeBoardId) {
      return;
    }
    persistActiveBoardId(boardId);
    setActiveBoardId(boardId);
  };

  const refreshBoards = async () => {
    const available = await fetchBoards();
    setBoards(available);
    return available;
  };

  const handleCreateBoard = async (name: string) => {
    try {
      const board = await createBoard(name);
      await refreshBoards();
      selectBoard(board.id);
      toast.success(t('notifications.boardCreated'));
      return board;
    } catch (err) {
      console.error('Error creating board:', err);
      reportError(t, err);
      return null;
    }
  };

  const handleRenameBoard = async (boardId: Id, name: string) => {
    try {
      await renameBoard(boardId, name);
      await refreshBoards();
      toast.success(t('notifications.boardRenamed'));
      return true;
    } catch (err) {
      console.error('Error renaming board:', err);
      reportError(t, err);
      return false;
    }
  };

  const handleDeleteBoard = async (boardId: Id) => {
    try {
      await deleteBoard(boardId);
      const remaining = await refreshBoards();
      const next = remaining.find(board => board.id !== boardId);
      persistActiveBoardId(next ? next.id : null);
      setActiveBoardId(next ? next.id : null);
      if (!next) {
        const current = await fetchCurrentBoard();
        await refreshBoards();
        persistActiveBoardId(current.id);
        setActiveBoardId(current.id);
      }
      toast.success(t('notifications.boardDeleted'));
      return true;
    } catch (err) {
      console.error('Error deleting board:', err);
      reportError(t, err);
      return false;
    }
  };

  const handleInviteToBoard = async (boardId: Id, email: string, role?: BoardRole) => {
    try {
      const invitation = await inviteToBoard(boardId, email, role);
      toast.info(t('notifications.boardInvitationSent'));
      return invitation;
    } catch (err) {
      console.error('Error sending an invitation:', err);
      reportError(t, err);
      return null;
    }
  };

  const handleRevokeInvitation = async (boardId: Id, invitationId: Id) => {
    try {
      await revokeBoardInvitation(boardId, invitationId);
      toast.success(t('notifications.boardInvitationRevoked'));
      return true;
    } catch (err) {
      console.error('Error revoking an invitation:', err);
      reportError(t, err);
      return false;
    }
  };

  const handleAcceptInvitation = async (invitationId: Id) => {
    try {
      const board = await acceptInvitation(invitationId);
      await refreshBoards();
      await refreshMyInvitations();
      selectBoard(board.id);
      toast.success(t('notifications.boardInvitationAccepted', { name: board.name }));
      return board;
    } catch (err) {
      console.error('Error accepting an invitation:', err);
      reportError(t, err);
      return null;
    }
  };

  const handleDeclineInvitation = async (invitationId: Id) => {
    try {
      await declineInvitation(invitationId);
      await refreshMyInvitations();
      toast.info(t('notifications.boardInvitationDeclined'));
      return true;
    } catch (err) {
      console.error('Error declining an invitation:', err);
      reportError(t, err);
      return false;
    }
  };

  const handleRemoveBoardMember = async (boardId: Id, userId: Id) => {
    try {
      const board = await removeBoardMember(boardId, userId);
      await refreshBoards();
      toast.success(t('notifications.boardMemberRemoved'));
      return board;
    } catch (err) {
      console.error('Error removing board member:', err);
      reportError(t, err);
      return null;
    }
  };

  return {
    boards,
    activeBoard,
    activeBoardId,
    isViewer,
    myInvitations,
    selectBoard,
    refreshBoards,
    refreshMyInvitations,
    createBoard: handleCreateBoard,
    renameBoard: handleRenameBoard,
    deleteBoard: handleDeleteBoard,
    inviteToBoard: handleInviteToBoard,
    revokeInvitation: handleRevokeInvitation,
    acceptInvitation: handleAcceptInvitation,
    declineInvitation: handleDeclineInvitation,
    removeBoardMember: handleRemoveBoardMember,
  };
}
