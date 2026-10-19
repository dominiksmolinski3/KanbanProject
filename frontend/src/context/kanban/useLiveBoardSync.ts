import { useEffect, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import BoardEvents from '../../services/boardEvents';
import { taskKeys } from '../../services/taskQueries';
import type { Id } from '../../types/api';

const LIVE_REFRESH_WINDOW_MS = 250;

interface LiveRefreshers {
  refreshTasks: () => Promise<void>;
  refreshBoard: () => Promise<void>;
}

export function useLiveBoardSync(activeBoardId: Id | null, refreshers: LiveRefreshers) {
  const queryClient = useQueryClient();
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
        queryClient.invalidateQueries({ queryKey: taskKeys.anyComments });
        return;
      }
      if (type === 'ATTACHMENTS') {
        queryClient.invalidateQueries({ queryKey: taskKeys.anyAttachments });
        return;
      }
      if (type === 'SUBTASKS') {
        queryClient.invalidateQueries({ queryKey: taskKeys.anySubtasks });
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
  }, [activeBoardId, queryClient]);
}
