import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { fetchActivity } from '../../services/activityApi';
import { logged } from '../../services/logged';
import type { Id, TaskActivity } from '../../types/api';

const NONE: TaskActivity[] = [];

export function useActivityFeed(boardId: Id | null | undefined) {
  const [paging, setPaging] = useState({ boardId, page: 0 });
  const page = paging.boardId === boardId ? paging.page : 0;

  const { data, isPending, isError } = useQuery({
    queryKey: ['activity', boardId ?? null, page],
    queryFn: () => logged('Error fetching the activity feed:', () => fetchActivity({ boardId, page })),
  });

  return {
    entries: data?.activities ?? NONE,
    page,
    setPage: (next: number) => setPaging({ boardId, page: next }),
    totalPages: data?.totalPages ?? 0,
    total: data?.totalEntries ?? 0,
    loading: isPending,
    failed: isError,
  };
}
