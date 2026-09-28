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

export function useBoards({ t, setError, setLoading }) {
  const [boards, setBoards] = useState([]);
  const [activeBoardId, setActiveBoardId] = useState(null);
  const [myInvitations, setMyInvitations] = useState([]);

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
        setError(err.message);
        setLoading(false);
      }
    };

    resolveBoard();
    refreshMyInvitations();
  }, [setError, setLoading]);

  const activeBoard = boards.find(board => board.id === activeBoardId) || null;
  const isViewer = activeBoard?.role === 'VIEWER';

  const selectBoard = (boardId) => {
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

  const handleCreateBoard = async (name) => {
    try {
      const board = await createBoard(name);
      await refreshBoards();
      selectBoard(board.id);
      toast.success(t('notifications.boardCreated'));
      return board;
    } catch (err) {
      console.error('Error creating board:', err);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      return null;
    }
  };

  const handleRenameBoard = async (boardId, name) => {
    try {
      await renameBoard(boardId, name);
      await refreshBoards();
      toast.success(t('notifications.boardRenamed'));
      return true;
    } catch (err) {
      console.error('Error renaming board:', err);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      return false;
    }
  };

  const handleDeleteBoard = async (boardId) => {
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
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      return false;
    }
  };

  const handleInviteToBoard = async (boardId, email, role) => {
    try {
      const invitation = await inviteToBoard(boardId, email, role);
      toast.info(t('notifications.boardInvitationSent'));
      return invitation;
    } catch (err) {
      console.error('Error sending an invitation:', err);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      return null;
    }
  };

  const handleRevokeInvitation = async (boardId, invitationId) => {
    try {
      await revokeBoardInvitation(boardId, invitationId);
      toast.success(t('notifications.boardInvitationRevoked'));
      return true;
    } catch (err) {
      console.error('Error revoking an invitation:', err);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      return false;
    }
  };

  const handleAcceptInvitation = async (invitationId) => {
    try {
      const board = await acceptInvitation(invitationId);
      await refreshBoards();
      await refreshMyInvitations();
      selectBoard(board.id);
      toast.success(t('notifications.boardInvitationAccepted', { name: board.name }));
      return board;
    } catch (err) {
      console.error('Error accepting an invitation:', err);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      return null;
    }
  };

  const handleDeclineInvitation = async (invitationId) => {
    try {
      await declineInvitation(invitationId);
      await refreshMyInvitations();
      toast.info(t('notifications.boardInvitationDeclined'));
      return true;
    } catch (err) {
      console.error('Error declining an invitation:', err);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      return false;
    }
  };

  const handleRemoveBoardMember = async (boardId, userId) => {
    try {
      const board = await removeBoardMember(boardId, userId);
      await refreshBoards();
      toast.success(t('notifications.boardMemberRemoved'));
      return board;
    } catch (err) {
      console.error('Error removing board member:', err);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
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
