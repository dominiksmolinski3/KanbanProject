import { useMemo, useRef, type SetStateAction } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { fetchColumns, fetchRows, fetchTasks } from '../../services/api';
import type { ColumnSummary, Id, RowSummary, Task } from '../../types/api';
import { boardKeys, byPosition, logged } from './boardQueries';
import { messageOf } from './reportError';
import type { BoardData } from './types';
import { useLiveBoardSync } from './useLiveBoardSync';

const NONE: never[] = [];

interface BoardDataDeps {
  activeBoardId: Id | null;
}

export type BoardRead = BoardData & { loading: boolean; error: string | null };

export function useBoardData({ activeBoardId }: BoardDataDeps): BoardRead {
  const queryClient = useQueryClient();
  const enabled = activeBoardId !== null;
  const localChanges = useRef(0);
  const keys = {
    columns: boardKeys.columns(activeBoardId),
    rows: boardKeys.rows(activeBoardId),
    tasks: boardKeys.tasks(activeBoardId),
  };

  const columnsQuery = useQuery({
    queryKey: keys.columns,
    enabled,
    queryFn: async () => byPosition(await logged('Error fetching columns:', fetchColumns)),
  });
  const rowsQuery = useQuery({
    queryKey: keys.rows,
    enabled,
    queryFn: async () => byPosition(await logged('Error fetching rows:', fetchRows)),
  });
  // A local edit made while this read was in flight wins over what the read brings back.
  const tasksQuery = useQuery({
    queryKey: keys.tasks,
    enabled,
    queryFn: async () => {
      const changesAtStart = localChanges.current;
      const fetched = await logged('Error fetching tasks:', fetchTasks);
      return changesAtStart === localChanges.current
        ? fetched
        : queryClient.getQueryData<Task[]>(keys.tasks) ?? fetched;
    },
  });

  const columns: ColumnSummary[] = columnsQuery.data ?? NONE;
  const rows: RowSummary[] = rowsQuery.data ?? NONE;
  const storedTasks: Task[] = tasksQuery.data ?? NONE;
  const tasks = useMemo(
    () => (rows.length > 0 ? storedTasks.map(task => (task.rowId ? task : { ...task, rowId: rows[0].id })) : storedTasks),
    [storedTasks, rows]
  );

  const loading = enabled && (columnsQuery.isPending || rowsQuery.isPending || tasksQuery.isPending);
  const failure = columnsQuery.error ?? tasksQuery.error;
  const error = failure ? messageOf(failure) : null;

  const writer = <T,>(key: readonly unknown[], onWrite?: () => void) => (update: SetStateAction<T[]>) => {
    onWrite?.();
    queryClient.setQueryData<T[]>(key, previous =>
      typeof update === 'function' ? (update as (value: T[]) => T[])(previous ?? []) : update
    );
  };

  const refreshTasks = async () => {
    await queryClient.invalidateQueries({ queryKey: keys.tasks });
  };

  const refreshBoard = async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: keys.columns }),
      queryClient.invalidateQueries({ queryKey: keys.rows }),
      queryClient.invalidateQueries({ queryKey: keys.tasks }),
    ]);
  };

  useLiveBoardSync(activeBoardId, { refreshTasks, refreshBoard });

  return {
    columns, setColumns: writer<ColumnSummary>(keys.columns),
    tasks, setTasks: writer<Task>(keys.tasks, () => { localChanges.current += 1; }),
    rows, setRows: writer<RowSummary>(keys.rows),
    refreshTasks,
    refreshBoard,
    loading,
    error,
  };
}
