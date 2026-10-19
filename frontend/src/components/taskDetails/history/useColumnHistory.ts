import { useEffect, useState } from 'react';
import { fetchColumns, getTaskColumnHistory, getTaskColumnTimeSpentSummary } from '../../../services/api';
import type { ColumnTimeSpent, Id, TaskColumnHistoryEntry } from '../../../types/api';

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
  const [history, setHistory] = useState<TaskColumnHistoryEntry[]>([]);
  const [timeSpent, setTimeSpent] = useState<ColumnTimeSpent[]>([]);
  const [loadingTimeSpent, setLoadingTimeSpent] = useState(true);

  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const data = await loadHistory(taskId);
        if (live) setHistory(data);
      } catch (error) {
        console.error('Error fetching column history:', error);
        if (live) setHistory([]);
      }
    })();
    (async () => {
      try {
        const data = await getTaskColumnTimeSpentSummary(taskId);
        if (live) setTimeSpent(data || []);
      } catch (error) {
        console.error('Error loading column time spent:', error);
      } finally {
        if (live) setLoadingTimeSpent(false);
      }
    })();
    return () => {
      live = false;
    };
  }, [taskId]);

  return { history, timeSpent, loadingTimeSpent };
}
