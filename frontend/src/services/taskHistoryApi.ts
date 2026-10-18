import { getJson } from './http';
import type { ColumnTimeSpent, Id, TaskColumnHistoryEntry } from '../types/api';

const TASKS = '/api/tasks';

export const getTaskColumnHistory = (taskId: Id) =>
  getJson<TaskColumnHistoryEntry[]>(`${TASKS}/${taskId}/column-history`);

export const getTaskColumnTimeSpentSummary = async (taskId: Id): Promise<ColumnTimeSpent[]> => {
  const history = await getTaskColumnHistory(taskId);
  if (!history || history.length === 0) {
    return [];
  }

  const sorted = [...history].sort((a, b) => new Date(a.changedAt).getTime() - new Date(b.changedAt).getTime());
  const byColumn: Record<Id, ColumnTimeSpent> = {};
  sorted.forEach((entry, index) => {
    const next = sorted[index + 1];
    const end = next ? new Date(next.changedAt) : new Date();
    byColumn[entry.columnId] ??= {
      columnId: entry.columnId,
      columnName: entry.columnName,
      totalTimeMs: 0
    };
    byColumn[entry.columnId].totalTimeMs += Math.max(0, end.getTime() - new Date(entry.changedAt).getTime());
  });

  return Object.values(byColumn);
};
