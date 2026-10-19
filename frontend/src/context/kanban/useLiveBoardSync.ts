import { useEffect, useRef } from 'react';
import BoardEvents from '../../services/boardEvents';
import type { Id } from '../../types/api';

const LIVE_REFRESH_WINDOW_MS = 250;

interface LiveRefreshers {
  refreshTasks: () => Promise<void>;
  refreshBoard: () => Promise<void>;
}

export function useLiveBoardSync(activeBoardId: Id | null, refreshers: LiveRefreshers) {
  const latest = useRef(refreshers);
  latest.current = refreshers;

  useEffect(() => {
    if (activeBoardId === null) {
      return undefined;
    }

    const events = new BoardEvents();
    let pending: ReturnType<typeof setTimeout> | null = null;
    let wanted: 'board' | 'tasks' | null = null;

    events.watch(activeBoardId, ({ type }) => {
      if (type === 'COMMENTS') {
        window.dispatchEvent(new CustomEvent('task-comments-changed'));
        return;
      }
      if (type === 'ATTACHMENTS') {
        window.dispatchEvent(new CustomEvent('task-attachments-changed'));
        return;
      }
      if (type === 'SUBTASKS') {
        window.dispatchEvent(new CustomEvent('task-subtasks-changed'));
      }
      wanted = wanted === 'board' || (type !== 'TASKS' && type !== 'SUBTASKS') ? 'board' : 'tasks';
      if (pending) {
        return;
      }
      pending = setTimeout(() => {
        const read = wanted;
        pending = null;
        wanted = null;
        if (read === 'board') {
          latest.current.refreshBoard();
        } else {
          latest.current.refreshTasks();
        }
      }, LIVE_REFRESH_WINDOW_MS);
    });

    return () => {
      if (pending) {
        clearTimeout(pending);
      }
      events.stop();
    };
  }, [activeBoardId]);
}
