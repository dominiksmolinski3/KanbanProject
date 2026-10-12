import { apiError } from './apiError';

const TASKS = '/api/tasks';

export const getTaskColumnHistory = async (taskId) => {
  try {
    const response = await fetch(`${TASKS}/${taskId}/column-history`);
    if (!response.ok) {
      throw await apiError(response);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error fetching column history for task ${taskId}:`, error);
    throw error;
  }
};

export const getTaskColumnTimeSpentSummary = async (taskId) => {
  try {
    const columnHistory = await getTaskColumnHistory(taskId);
    if (!columnHistory || columnHistory.length === 0) {
      return [];
    }

    const timeSpentByColumn = {};

    const sortedHistory = [...columnHistory].sort((a, b) =>
      new Date(a.changedAt) - new Date(b.changedAt)
    );

    for (let i = 0; i < sortedHistory.length - 1; i++) {
      const entry = sortedHistory[i];
      const nextEntry = sortedHistory[i + 1];
      const columnId = entry.columnId;

      const columnName = entry.columnName || `Column ${columnId}`;

      const startTime = new Date(entry.changedAt);
      const endTime = new Date(nextEntry.changedAt);
      const timeSpentMs = Math.max(0, endTime - startTime);
      
      if (!timeSpentByColumn[columnId]) {
        timeSpentByColumn[columnId] = {
          columnId,
          columnName,
          totalTimeMs: 0
        };
      }
      
      timeSpentByColumn[columnId].totalTimeMs += timeSpentMs;
    }
    
    const lastEntry = sortedHistory[sortedHistory.length - 1];
    if (!timeSpentByColumn[lastEntry.columnId]) {
      const columnId = lastEntry.columnId;
      const columnName = lastEntry.columnName || `Column ${columnId}`;

      timeSpentByColumn[columnId] = {
        columnId,
        columnName,
        totalTimeMs: 0
      };
    }

    const lastEntryTime = new Date(lastEntry.changedAt);
    const now = new Date();
    const currentTimeMs = Math.max(0, now - lastEntryTime);
    timeSpentByColumn[lastEntry.columnId].totalTimeMs += currentTimeMs;
    
    return Object.values(timeSpentByColumn).map(column => {
      const days = Math.floor(column.totalTimeMs / (1000 * 60 * 60 * 24));
      const hours = Math.floor((column.totalTimeMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
      const minutes = Math.floor((column.totalTimeMs % (1000 * 60 * 60)) / (1000 * 60));
      
      return {
        ...column,
        formattedTime: days > 0 
          ? `${days}d ${hours}h ${minutes}m` 
          : hours > 0 
            ? `${hours}h ${minutes}m` 
            : `${minutes}m`
      };
    });
  } catch (error) {
    console.error(`Error calculating time spent in columns for task ${taskId}:`, error);
    throw error;
  }
};
