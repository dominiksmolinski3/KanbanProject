import { useCallback, useEffect, useState } from 'react';
import { fetchBoardInvitations } from '../../services/boardApi';
import type { BoardInvitation, Id } from '../../types/api';

export function usePendingInvitations(boardId: Id | undefined, owned: boolean) {
  const [invitations, setInvitations] = useState<BoardInvitation[]>([]);

  const reload = useCallback(async () => {
    if (!boardId || !owned) {
      setInvitations([]);
      return;
    }
    try {
      setInvitations(await fetchBoardInvitations(boardId));
    } catch (err) {
      console.error('Error fetching board invitations:', err);
      setInvitations([]);
    }
  }, [boardId, owned]);

  useEffect(() => {
    reload();
  }, [reload]);

  return { invitations, reload };
}
