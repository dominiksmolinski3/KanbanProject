import { read } from './http';

const TASKS = '/api/tasks';

const MINUTE_MS = 60 * 1000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

const formatDuration = (ms) => {
  const days = Math.floor(ms / DAY_MS);
  const hours = Math.floor((ms % DAY_MS) / HOUR_MS);
  const minutes = Math.floor((ms % HOUR_MS) / MINUTE_MS);
  if (days > 0) return `${days}d ${hours}h ${minutes}m`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  return `${minutes}m`;
};

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
      columnName: entry.columnName || `Column ${entry.columnId}`,
      totalTimeMs: 0
    };
    byColumn[entry.columnId].totalTimeMs += Math.max(0, end - new Date(entry.changedAt));
  });

  return Object.values(byColumn).map(column => ({ ...column, formattedTime: formatDuration(column.totalTimeMs) }));
};
