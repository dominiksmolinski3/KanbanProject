import { useEffect, useRef, useState } from 'react';
import { fetchColumns, fetchRows, fetchTasks } from '../../services/api';
import BoardEvents from '../../services/boardEvents';

const LIVE_REFRESH_WINDOW_MS = 250;

export function useBoardData({ activeBoardId, setLoading, setError }) {
  const [columns, setColumns] = useState([]);
  const [tasks, setTasks] = useState([]);
  const [rows, setRows] = useState([]);
  const [columnMap, setColumnMap] = useState({});

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
        const newColumnMap = {};
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
        setError(err.message);
        setLoading(false);
      }
    };

    loadData();
    return () => {
      superseded = true;
    };
  }, [activeBoardId, setLoading, setError]);

  const refreshTasks = async () => {
    try {
      const tasksData = await fetchTasks();
      setTasks(tasksData);
      setLoading(false);
    } catch (err) {
      console.error('Error refreshing tasks:', err);
      setError(err.message);
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
      setError(err.message);
      setLoading(false);
    }
  };

  const liveRefresh = useRef(null);
  liveRefresh.current = { refreshTasks, refreshBoard };

  useEffect(() => {
    if (activeBoardId === null) {
      return undefined;
    }

    const events = new BoardEvents();
    let pending = null;
    let wanted = null;

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
    tasks, setTasks,
    rows, setRows,
    columnMap, setColumnMap,
    refreshTasks,
    refreshBoard,
  };
}
