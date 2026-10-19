import { useQuery } from '@tanstack/react-query';
import { fetchColumns, getTaskColumnHistory, getTaskColumnTimeSpentSummary } from '../../../services/api';
import { logged } from '../../../services/logged';
import { taskKeys } from '../../../services/taskQueries';
import type { ColumnTimeSpent, Id, TaskColumnHistoryEntry } from '../../../types/api';

const NO_HISTORY: TaskColumnHistoryEntry[] = [];
const NO_TIME: ColumnTimeSpent[] = [];

async function loadHistory(taskId: Id): Promise<TaskColumnHistoryEntry[]> {
  const history = await getTaskColumnHistory(taskId);
  if (!history || history.length === 0) {
    return [];
  }

  let names: Record<string, string> = {};
  try {
    const columns = (await fetchColumns()) || [];
    names = Object.fromEntries(columns.map(column => [column.id, column.name]));
  } catch {
    names = {};
  }

  return history
    .map(item => ({ ...item, columnName: item.columnName || names[item.columnId] }))
    .filter((item, index, all) => all.findIndex(other =>
      Math.abs(new Date(other.changedAt).getTime() - new Date(item.changedAt).getTime()) < 1000 &&
      other.columnId === item.columnId
    ) === index);
}

export function useColumnHistory(taskId: Id) {
  const history = useQuery({
    queryKey: taskKeys.history(taskId),
    queryFn: () => logged('Error fetching column history:', () => loadHistory(taskId)),
  });
  const timeSpent = useQuery({
    queryKey: taskKeys.timeSpent(taskId),
    queryFn: async () =>
      (await logged('Error loading column time spent:', () => getTaskColumnTimeSpentSummary(taskId))) || NO_TIME,
  });

  return {
    history: history.data ?? NO_HISTORY,
    timeSpent: timeSpent.data ?? NO_TIME,
    loadingTimeSpent: timeSpent.isPending,
  };
}
