import { read } from './http';

const TASKS = '/api/tasks';

export const getTaskColumnHistory = async (taskId) => {
  const response = await fetch(`${TASKS}/${taskId}/column-history`);
  return read(response);
};

export const getTaskColumnTimeSpentSummary = async (taskId) => {
  const history = await getTaskColumnHistory(taskId);
  if (!history || history.length === 0) {
    return [];
  }

  const sorted = [...history].sort((a, b) => new Date(a.changedAt) - new Date(b.changedAt));
  const byColumn = {};
  sorted.forEach((entry, index) => {
    const next = sorted[index + 1];
    const end = next ? new Date(next.changedAt) : new Date();
    byColumn[entry.columnId] ??= {
      columnId: entry.columnId,
      columnName: entry.columnName,
      totalTimeMs: 0
    };
    byColumn[entry.columnId].totalTimeMs += Math.max(0, end - new Date(entry.changedAt));
  });

  return Object.values(byColumn);
};
