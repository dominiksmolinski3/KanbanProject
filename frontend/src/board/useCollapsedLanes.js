import { useCallback, useEffect, useState } from 'react';

const storageKey = (boardId) => `kanban.collapsedLanes.${boardId ?? 'default'}`;

function read(boardId) {
  try {
    const stored = JSON.parse(localStorage.getItem(storageKey(boardId)) || '[]');
    return new Set(Array.isArray(stored) ? stored.map(String) : []);
  } catch {
    return new Set();
  }
}

export default function useCollapsedLanes(boardId) {
  const [collapsed, setCollapsed] = useState(() => read(boardId));

  useEffect(() => {
    setCollapsed(read(boardId));
  }, [boardId]);

  const toggle = useCallback((laneId) => {
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

  const isCollapsed = useCallback((laneId) => collapsed.has(String(laneId)), [collapsed]);

  return { isCollapsed, toggle };
}
