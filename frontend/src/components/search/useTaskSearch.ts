import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { toInstant } from '../../board/deadlines';
import { searchTasks } from '../../services/api';
import { getActiveBoardId } from '../../services/activeBoard';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import type { SearchFilters } from './useSearchFilters';

const DEBOUNCE_MS = 300;

export function useTaskSearch(isOpen: boolean, filters: SearchFilters) {
  const activeBoardId = getActiveBoardId();
  const { term, labels, assignees, completed, deadlineFrom, deadlineTo, page } = filters;
  const wanted = useDebouncedValue({ term, labels, assignees, completed, deadlineFrom, deadlineTo, page }, DEBOUNCE_MS);

  const query = useQuery({
    queryKey: ['search', activeBoardId, wanted],
    enabled: isOpen,
    placeholderData: keepPreviousData,
    queryFn: () => searchTasks({
      q: wanted.term,
      labels: wanted.labels,
      assignees: wanted.assignees,
      completed: wanted.completed === '' ? null : wanted.completed === 'true',
      deadlineFrom: toInstant(wanted.deadlineFrom),
      deadlineTo: toInstant(wanted.deadlineTo),
      page: wanted.page
    }),
  });

  return {
    results: query.isError ? null : query.data ?? null,
    isSearching: query.isFetching,
    failed: query.isError,
  };
}
