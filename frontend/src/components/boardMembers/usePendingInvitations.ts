import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchBoardInvitations } from '../../services/boardApi';
import { logged } from '../../services/logged';
import type { BoardInvitation, Id } from '../../types/api';

const NONE: BoardInvitation[] = [];

export function usePendingInvitations(boardId: Id | undefined, owned: boolean) {
  const queryClient = useQueryClient();
  const key = ['boardInvitations', boardId ?? null] as const;
  const enabled = Boolean(boardId) && owned;

  const query = useQuery({
    queryKey: key,
    enabled,
    queryFn: () => logged('Error fetching board invitations:', () => fetchBoardInvitations(boardId as Id)),
  });

  return {
    invitations: enabled ? query.data ?? NONE : NONE,
    reload: () => queryClient.invalidateQueries({ queryKey: key }),
  };
}
