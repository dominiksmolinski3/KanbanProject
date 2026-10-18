import { useEffect, useRef, useState } from 'react';
import { searchTasks } from '../../services/api';
import type { TaskSearchResults } from '../../types/api';
import type { SearchFilters } from './useSearchFilters';

const DEBOUNCE_MS = 300;

export function useTaskSearch(isOpen: boolean, filters: SearchFilters) {
  const { term, labels, assignees, completed, deadlineFrom, deadlineTo, page } = filters;
  const [results, setResults] = useState<TaskSearchResults | null>(null);
  const [isSearching, setIsSearching] = useState(false);
  const [failed, setFailed] = useState(false);
  const latestRequest = useRef(0);

  useEffect(() => {
    if (!isOpen) {
      return undefined;
    }
    const sequence = latestRequest.current + 1;
    latestRequest.current = sequence;

    const timer = setTimeout(() => {
      setIsSearching(true);
      searchTasks({
        q: term,
        labels,
        assignees,
        completed: completed === '' ? null : completed === 'true',
        deadlineFrom: deadlineFrom || null,
        deadlineTo: deadlineTo || null,
        page
      })
        .then((found) => {
          if (latestRequest.current === sequence) {
            setResults(found);
            setFailed(false);
          }
        })
        .catch(() => {
          if (latestRequest.current === sequence) {
            setResults(null);
            setFailed(true);
          }
        })
        .finally(() => {
          if (latestRequest.current === sequence) {
            setIsSearching(false);
          }
        });
    }, DEBOUNCE_MS);

    return () => clearTimeout(timer);
  }, [isOpen, term, labels, assignees, completed, deadlineFrom, deadlineTo, page]);

  return { results, isSearching, failed };
}
