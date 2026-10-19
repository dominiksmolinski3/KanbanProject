import { useCallback, useEffect, useState } from 'react';
import { fetchActivity } from '../../services/activityApi';
import type { Id, TaskActivity } from '../../types/api';

export function useActivityFeed(boardId: Id | null | undefined) {
  const [entries, setEntries] = useState<TaskActivity[]>([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setFailed(false);
    try {
      const results = await fetchActivity({ boardId, page });
      setEntries(results.activities);
      setTotalPages(results.totalPages);
      setTotal(results.totalEntries);
    } catch (err) {
      console.error('Error fetching the activity feed:', err);
      setFailed(true);
      setEntries([]);
    } finally {
      setLoading(false);
    }
  }, [boardId, page]);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    setPage(0);
  }, [boardId]);

  return { entries, page, setPage, totalPages, total, loading, failed };
}
