import { useQuery } from '@tanstack/react-query';
import { useKanban } from '../../context/KanbanContext';
import { useUsers } from '../../hooks/useUsers';
import { getAllLabels } from '../../services/api';
import type { User } from '../../types/api';

const NO_LABELS: string[] = [];
const NO_USERS: User[] = [];

export function useFacetOptions(isOpen: boolean) {
  const { activeBoardId } = useKanban();
  const labels = useQuery({
    queryKey: ['labels', activeBoardId],
    enabled: isOpen,
    queryFn: async () => {
      const boardLabels = await getAllLabels();
      return Array.isArray(boardLabels) ? boardLabels : NO_LABELS;
    },
  });
  const users = useUsers(isOpen);

  // Chips for either are offered only when both could be read.
  if (labels.isError || users.isError) {
    return { labels: NO_LABELS, users: NO_USERS };
  }
  return { labels: labels.data ?? NO_LABELS, users: users.data ?? NO_USERS };
}
