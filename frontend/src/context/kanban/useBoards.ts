import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
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
import { logged } from '../../services/logged';
import { boardsKeys } from './boardQueries';
import { messageOf, reportError } from './reportError';
import type { Translate } from './types';

const NO_BOARDS: Board[] = [];
const NO_INVITATIONS: BoardInvitation[] = [];

export function useBoards({ t }: { t: Translate }) {
  const queryClient = useQueryClient();
  const [activeBoardId, setActiveBoardId] = useState<Id | null>(null);

  const boardsQuery = useQuery({
    queryKey: boardsKeys.list,
    queryFn: () => logged('Error resolving the board:', fetchBoards),
  });
  const currentQuery = useQuery({
    queryKey: boardsKeys.current,
    queryFn: () => logged('Error resolving the board:', fetchCurrentBoard),
  });
  const invitationsQuery = useQuery({
    queryKey: boardsKeys.invitations,
    queryFn: () => logged('Error fetching invitations:', fetchMyInvitations),
  });

  const boards = boardsQuery.data ?? NO_BOARDS;
  const myInvitations = invitationsQuery.data ?? NO_INVITATIONS;
  const failure = boardsQuery.error ?? currentQuery.error;
  const error = failure ? messageOf(failure) : null;

  const available = boardsQuery.data;
  const current = currentQuery.data;
  useEffect(() => {
    if (activeBoardId !== null || !available || !current) {
      return;
    }
    const remembered = getActiveBoardId();
    const chosen = available.some(board => board.id === remembered) ? remembered : current.id;
    persistActiveBoardId(chosen);
    setActiveBoardId(chosen);
  }, [activeBoardId, available, current]);

  const activeBoard = boards.find(board => board.id === activeBoardId) || null;
  const isViewer = activeBoard?.role === 'VIEWER';

  const selectBoard = (boardId: Id) => {
    if (boardId === activeBoardId) {
      return;
    }
    persistActiveBoardId(boardId);
    setActiveBoardId(boardId);
  };

  // fetchQuery joins a read already in flight, which can predate the write this follows.
  const readAfresh = async <T>(queryKey: readonly unknown[], queryFn: () => Promise<T>) => {
    await queryClient.cancelQueries({ queryKey, exact: true });
    return queryClient.fetchQuery({ queryKey, queryFn, staleTime: 0 });
  };

  const refreshBoards = () => readAfresh(boardsKeys.list, fetchBoards);

  const refreshMyInvitations = async () => {
    try {
      return await readAfresh(boardsKeys.invitations, fetchMyInvitations);
    } catch (err) {
      console.error('Error fetching invitations:', err);
      return [];
    }
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

  const moveOffBoard = async (remaining: { id: Id }[], goneId: Id) => {
    const next = remaining.find(board => board.id !== goneId);
    persistActiveBoardId(next ? next.id : null);
    setActiveBoardId(next ? next.id : null);
    if (!next) {
      const replacement = await fetchCurrentBoard();
      await refreshBoards();
      persistActiveBoardId(replacement.id);
      setActiveBoardId(replacement.id);
    }
  };

  const handleBoardsChanged = async () => {
    try {
      const before = boards.find(board => board.id === activeBoardId);
      const remaining = await refreshBoards();
      queryClient.invalidateQueries({ queryKey: boardsKeys.invitations });
      if (activeBoardId === null) {
        return false;
      }
      const after = remaining.find(board => board.id === activeBoardId);
      if (!after) {
        await moveOffBoard(remaining, activeBoardId);
        toast.info(t('notifications.boardNoLongerAvailable'));
        return false;
      }
      // Removing a member unassigns their cards with no board event, so a changed entry can mean changed cards.
      return JSON.stringify(before) !== JSON.stringify(after);
    } catch (err) {
      console.error('Error refreshing the boards:', err);
      return false;
    }
  };

  const handleDeleteBoard = async (boardId: Id) => {
    try {
      await deleteBoard(boardId);
      await moveOffBoard(await refreshBoards(), boardId);
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
      const remaining = await refreshBoards();
      if (boardId === activeBoardId && !remaining.some(candidate => candidate.id === boardId)) {
        await moveOffBoard(remaining, boardId);
      }
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
    error,
    selectBoard,
    refreshBoards,
    refreshMyInvitations,
    handleBoardsChanged,
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
