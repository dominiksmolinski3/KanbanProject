import { useEffect, useRef } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import BoardEvents from '../../services/boardEvents';
import { taskKeys } from '../../services/taskQueries';
import type { Id } from '../../types/api';

const LIVE_REFRESH_WINDOW_MS = 250;

interface LiveRefreshers {
  refreshTasks: () => Promise<void>;
  refreshBoard: () => Promise<void>;
  onBoardsChanged?: () => Promise<boolean>;
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

    const refreshSoon = (scope: 'board' | 'tasks') => {
      wanted = wanted === 'board' ? 'board' : scope;
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
    };

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
      refreshSoon(type === 'TASKS' || type === 'SUBTASKS' ? 'tasks' : 'board');
    }, async () => {
      const { onBoardsChanged } = latest.current;
      if (!onBoardsChanged || await onBoardsChanged()) {
        latest.current.refreshBoard();
      }
    }, () => refreshSoon('board'));

    return () => {
      if (pending) {
        clearTimeout(pending);
      }
      events.stop();
    };
  }, [activeBoardId, queryClient]);
}
