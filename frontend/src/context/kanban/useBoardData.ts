import { useEffect, useRef, useState, type SetStateAction } from 'react';
import { fetchColumns, fetchRows, fetchTasks } from '../../services/api';
import BoardEvents from '../../services/boardEvents';
import type { ColumnSummary, Id, RowSummary, Task } from '../../types/api';
import { messageOf } from './reportError';
import type { BoardData, SetState } from './types';

const LIVE_REFRESH_WINDOW_MS = 250;

interface BoardDataDeps {
  activeBoardId: Id | null;
  setLoading: SetState<boolean>;
  setError: SetState<string | null>;
}

export function useBoardData({ activeBoardId, setLoading, setError }: BoardDataDeps): BoardData {
  const [columns, setColumns] = useState<ColumnSummary[]>([]);
  const [tasks, setTasks] = useState<Task[]>([]);
  const [rows, setRows] = useState<RowSummary[]>([]);
  const [columnMap, setColumnMap] = useState<Record<string, Id>>({});
  const refreshes = useRef(0);
  const localChanges = useRef(0);

  const setTasksLocally = (update: SetStateAction<Task[]>) => {
    localChanges.current += 1;
    setTasks(update);
  };

  useEffect(() => {
    if (activeBoardId === null) {
      return;
    }

    let superseded = false;

    const loadData = async () => {
      try {
        setLoading(true);
        const [columnsData, rowsData, tasksData] = await Promise.all([
          fetchColumns(),
          fetchRows().catch(rowErr => {
            console.error('Error fetching rows:', rowErr);
            return [];
          }),
          fetchTasks(),
        ]);
        if (superseded) {
          return;
        }

        const sortedColumns = columnsData.sort((a, b) => a.position - b.position);
        const newColumnMap: Record<string, Id> = {};
        sortedColumns.forEach(column => {
          const columnKey = column.name.toLowerCase().replace(/\s+/g, '-');
          newColumnMap[columnKey] = column.id;
        });

        setColumns(sortedColumns);
        setColumnMap(newColumnMap);

        const sortedRows = rowsData.sort((a, b) => a.position - b.position);
        setRows(sortedRows);

        if (sortedRows.length > 0) {
          const defaultRowId = sortedRows[0].id;
          const updatedTasks = tasksData.map(task =>
            (!task.rowId || task.rowId === null) ? { ...task, rowId: defaultRowId } : task
          );
          setTasks(updatedTasks);
        } else {
          setTasks(tasksData);
        }

        setLoading(false);
      } catch (err) {
        if (superseded) {
          return;
        }
        setError(messageOf(err));
        setLoading(false);
      }
    };

    loadData();
    return () => {
      superseded = true;
    };
  }, [activeBoardId, setLoading, setError]);

  const refreshTasks = async () => {
    const started = ++refreshes.current;
    const changesAtStart = localChanges.current;
    try {
      const tasksData = await fetchTasks();
      const stillCurrent = started === refreshes.current && changesAtStart === localChanges.current;
      if (stillCurrent) {
        setTasks(tasksData);
      }
      setLoading(false);
    } catch (err) {
      console.error('Error refreshing tasks:', err);
      setError(messageOf(err));
      setLoading(false);
    }
  };

  const refreshBoard = async () => {
    try {
      setLoading(true);
      const [columnsData, rowsData] = await Promise.all([fetchColumns(), fetchRows()]);
      const sortedColumns = columnsData.sort((a, b) => a.position - b.position);
      setColumns(sortedColumns);
      const sortedRows = rowsData.sort((a, b) => a.position - b.position);
      setRows(sortedRows);
      refreshTasks();
      setLoading(false);
    } catch (err) {
      console.error('Error refreshing board data:', err);
      setError(messageOf(err));
      setLoading(false);
    }
  };

  const liveRefresh = useRef({ refreshTasks, refreshBoard });
  liveRefresh.current = { refreshTasks, refreshBoard };

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
        const handlers = liveRefresh.current;
        if (read === 'board') {
          handlers.refreshBoard();
        } else {
          handlers.refreshTasks();
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

  return {
    columns, setColumns,
    tasks, setTasks: setTasksLocally,
    rows, setRows,
    columnMap, setColumnMap,
    refreshTasks,
    refreshBoard,
  };
}
