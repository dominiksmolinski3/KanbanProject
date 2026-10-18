import { useCallback, useEffect, useState } from 'react';
import type { Id } from '../types/api';

type LaneId = Id | string;

const storageKey = (boardId: Id | null | undefined) => `kanban.collapsedLanes.${boardId ?? 'default'}`;

function read(boardId: Id | null | undefined): Set<string> {
  try {
    const stored = JSON.parse(localStorage.getItem(storageKey(boardId)) || '[]');
    return new Set(Array.isArray(stored) ? stored.map(String) : []);
  } catch {
    return new Set();
  }
}

export default function useCollapsedLanes(boardId: Id | null | undefined) {
  const [collapsed, setCollapsed] = useState(() => read(boardId));

  useEffect(() => {
    setCollapsed(read(boardId));
  }, [boardId]);

  const toggle = useCallback((laneId: LaneId) => {
    setCollapsed((previous) => {
      const next = new Set(previous);
      const key = String(laneId);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      try {
        localStorage.setItem(storageKey(boardId), JSON.stringify([...next]));
      } catch {
        // A lane that cannot be remembered still collapses for this visit.
      }
      return next;
    });
  }, [boardId]);

  const isCollapsed = useCallback((laneId: LaneId) => collapsed.has(String(laneId)), [collapsed]);

  return { isCollapsed, toggle };
}
