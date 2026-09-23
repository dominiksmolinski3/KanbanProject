const API_ENDPOINTS = {
  BOARDS: '/api/boards',
  COLUMNS: '/api/columns',
  TASKS: '/api/tasks',
  USERS: '/api/users',
  ROWS: '/api/rows',
  SUBTASKS: '/api/subtasks'
};

const ACTIVE_BOARD_KEY = 'activeBoardId';

export const getActiveBoardId = () => {
  const stored = localStorage.getItem(ACTIVE_BOARD_KEY);
  return stored ? Number(stored) : null;
};

export const setActiveBoardId = (boardId) => {
  if (boardId === null || boardId === undefined) {
    localStorage.removeItem(ACTIVE_BOARD_KEY);
  } else {
    localStorage.setItem(ACTIVE_BOARD_KEY, String(boardId));
  }
};

const onActiveBoard = (path) => {
  const boardId = getActiveBoardId();
  if (!boardId) {
    return path;
  }
  return `${path}${path.includes('?') ? '&' : '?'}boardId=${boardId}`;
};

export const fetchColumns = async (retries = 3) => {
  while (retries > 0) {
    try {
      const response = await fetch(onActiveBoard(API_ENDPOINTS.COLUMNS));
      if (!response.ok) {
        throw new Error(`Error fetching columns: ${response.status}`);
      }
      return await response.json();
    } catch (error) {
      console.error('Error fetching columns:', error);
      if (retries === 1) throw error;
      retries--;
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
  }
};

export const addColumn = async (name, wipLimit) => {
  try {
    const response = await fetch(onActiveBoard(API_ENDPOINTS.COLUMNS), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        name,
        wipLimit: parseInt(wipLimit) || 0
      })
    });
    
    if (!response.ok) {
      throw new Error(`Error adding column: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error('Error adding column:', error);
    throw error;
  }
};

export const updateColumnWipLimit = async (columnId, wipLimit) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.COLUMNS}/${columnId}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        wipLimit: parseInt(wipLimit)
      })
    });

    if (response.status >= 200 && response.status < 300) {
      try {
        return await response.json();
      } catch (parseError) {
        console.warn('JSON parse error but update likely succeeded:', parseError);
        return {
          id: columnId,
          wipLimit: parseInt(wipLimit)
        };
      }
    }

    throw new Error(`Error updating WIP limit: ${response.status}`);
  } catch (error) {
    console.error(`Error updating WIP limit for column ${columnId}:`, error);
    throw error;
  }
};

export const updateColumnPosition = async (columnId, position) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.COLUMNS}/${columnId}/position/${position}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw new Error(`Error updating column position: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error updating position for column ${columnId}:`, error);
    throw error;
  }
};

export const deleteColumn = async (columnId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.COLUMNS}/${columnId}`, {
      method: 'DELETE'
    });
    
    if (!response.ok && response.status !== 404) {
      throw new Error(`Error deleting column: ${response.status}`);
    }
    
    return true;
  } catch (error) {
    console.error(`Error deleting column ${columnId}:`, error);
    throw error;
  }
};

export const updateColumnName = async (id, name) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.COLUMNS}/${id}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ name }),
    });
    
    if (!response.ok) {
      throw new Error(`Failed to update column: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error('Error updating column name:', error);
    throw error;
  }
};

export const fetchTasks = async () => {
  try {
    const response = await fetch(onActiveBoard(API_ENDPOINTS.TASKS));
    if (!response.ok) {
      throw new Error(`Error fetching tasks: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error('Error fetching tasks:', error);
    throw error;
  }
};

export const fetchTask = async (taskId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}`);
    if (!response.ok) {
      throw new Error(`Error fetching task: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error fetching task ${taskId}:`, error);
    throw error;
  }
};

export const addTask = async (title, columnId, deadline = null) => {
  try {
    const response = await fetch(onActiveBoard(API_ENDPOINTS.TASKS), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        title,
        column: {
          id: columnId
        },
        deadline: deadline
      })
    });
    
    if (!response.ok) {
      throw new Error(`Error adding task: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error('Error adding task:', error);
    throw error;
  }
};

export const updateTask = async (taskId, taskData) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(taskData)
    });

    if (response.status === 409) {
      throw new ConcurrentModificationError('task');
    }
    if (!response.ok) {
      throw new Error(`Error updating task: ${response.status}`);
    }

    return await response.json();
  } catch (error) {
    console.error(`Error updating task ${taskId}:`, error);
    throw error;
  }
};

export const updateTaskColumn = async (taskId, columnId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        column: {
          id: columnId
        }
      })
    });
    
    if (!response.ok) {
      try {
        const errorData = await response.json();
        console.error("Error details:", errorData);
        throw new Error(`Error updating task column: ${response.status} - ${errorData.message || 'Unknown error'}`);
      } catch (parseError) {
        console.warn("Failed to parse error response:", parseError.message);
        
        if (response.status === 400) {
          throw new Error(`Error updating task column: ${response.status} - Column WIP limit exceeded`, { cause: parseError });
        } else {
          throw new Error(`Error updating task column: ${response.status} - Unknown error`, { cause: parseError });
        }
      }
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error updating task ${taskId}:`, error);
    throw error;
  }
};

export const updateTaskPosition = async (taskId, position) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}/position/${position}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw new Error(`Error updating task position: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error updating position for task ${taskId}:`, error);
    throw error;
  }
};

export class ConcurrentModificationError extends Error {
  constructor(what = 'item') {
    super(`The ${what} was changed by someone else`);
    this.name = 'ConcurrentModificationError';
  }
}

const reorder = async (endpoint, orderedIds, what) => {
  const response = await fetch(`${endpoint}/positions`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ orderedIds })
  });

  if (response.status === 409) {
    throw new ConcurrentModificationError(what);
  }
  if (!response.ok) {
    throw new Error(`Error reordering ${what}s: ${response.status}`);
  }

  return await response.json();
};

export const reorderTasks = (orderedIds) => reorder(API_ENDPOINTS.TASKS, orderedIds, 'task');
export const reorderColumns = (orderedIds) => reorder(API_ENDPOINTS.COLUMNS, orderedIds, 'column');
export const reorderRows = (orderedIds) => reorder(API_ENDPOINTS.ROWS, orderedIds, 'swimlane');

export class WipLimitExceededError extends Error {
  constructor(status) {
    super(`WIP limit reached for user ${status?.userId}`);
    this.name = 'WipLimitExceededError';
    this.status = status;
  }
}

export const assignUserToTask = async (taskId, userId) => {
  const wipStatus = await getUserWipStatus(userId);

  if (!wipStatus.withinLimit) {
    throw new WipLimitExceededError(wipStatus);
  }

  const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}/user/${userId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json'
    }
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));

    if (response.status === 400 && errorData.code === 'USER_WIP_LIMIT_EXCEEDED') {
      throw new WipLimitExceededError(wipStatus);
    }

    throw new Error(`${response.status}: Failed to assign user to task`);
  }

  return await response.json();
};

export async function getUserWipStatus(userId) {
  try {
    const response = await fetch(`${API_ENDPOINTS.USERS}/${userId}/wip-status`);

    if (!response.ok) {
      throw new Error('Failed to check user WIP status');
    }

    return await response.json();
  } catch (error) {
    console.error('Error checking user WIP status:', error);
    throw error;
  }
}

export async function updateUserWipLimit(userId, wipLimit) {
  try {
    const response = await fetch(`${API_ENDPOINTS.USERS}/${userId}/wip-limit`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(parseInt(wipLimit)), 
    });

    if (!response.ok) {
      throw new Error('Failed to update user WIP limit');
    }

    return await response.json();
  } catch (error) {
    console.error('Error updating user WIP limit:', error);
    throw error;
  }
}

export async function updateUserLocale(userId, locale) {
  const response = await fetch(`${API_ENDPOINTS.USERS}/${userId}`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ locale }),
  });

  if (!response.ok) {
    throw new Error('Failed to update the account language');
  }

  return await response.json();
}

export const removeUserFromTask = async (taskId, userId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}/user/${userId}`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw new Error(`Error removing user: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error removing user from task ${taskId}:`, error);
    throw error;
  }
};

export const addLabelToTask = async (taskId, label) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}/label/${label}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw new Error(`Error adding label to task: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error adding label to task ${taskId}:`, error);
    throw error;
  }
};

export const removeLabelFromTask = async (taskId, label) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}/label/${label}`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw new Error(`Error removing label from task: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error removing label from task ${taskId}:`, error);
    throw error;
  }
};

export const updateTaskLabels = async (taskId, labels) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}/labels`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(labels)
    });
    
    if (!response.ok) {
      throw new Error(`Error updating task labels: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error updating labels for task ${taskId}:`, error);
    throw error;
  }
};

export const getAllLabels = async () => {
  try {
    const response = await fetch(onActiveBoard(`${API_ENDPOINTS.TASKS}/get/all/labels`));
    if (!response.ok) {
      throw new Error(`Error fetching labels: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error('Error fetching all labels:', error);
    throw error;
  }
};

export const MAX_SEARCH_PAGE_SIZE = 100;

export const SEARCH_PAGE_SIZE = 25;

export const searchTasks = async (filters = {}) => {
  const {
    q = '',
    labels = [],
    assignees = [],
    completed = null,
    deadlineFrom = null,
    deadlineTo = null,
    page = 0,
    size = SEARCH_PAGE_SIZE
  } = filters;

  if (size < 1 || size > MAX_SEARCH_PAGE_SIZE) {
    throw new Error(`Search page size must be between 1 and ${MAX_SEARCH_PAGE_SIZE}`);
  }

  const params = new URLSearchParams();
  if (q && q.trim()) {
    params.set('q', q.trim());
  }
  labels.forEach((label) => params.append('label', label));
  assignees.forEach((userId) => params.append('assignee', String(userId)));
  if (completed !== null && completed !== undefined) {
    params.set('completed', String(completed));
  }
  if (deadlineFrom) {
    params.set('deadlineFrom', deadlineFrom);
  }
  if (deadlineTo) {
    params.set('deadlineTo', deadlineTo);
  }
  params.set('page', String(page));
  params.set('size', String(size));

  const response = await fetch(
    onActiveBoard(`${API_ENDPOINTS.TASKS}/search?${params.toString()}`)
  );

  if (!response.ok) {
    throw new Error(`Error searching tasks: ${response.status}`);
  }

  return await response.json();
};

export const deleteTask = async (taskId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}`, {
      method: 'DELETE'
    });
    
    if (!response.ok && response.status !== 404) {
      throw new Error(`Error deleting task: ${response.status}`);
    }
    
    return true;
  } catch (error) {
    console.error(`Error deleting task ${taskId}:`, error);
    throw error;
  }
};

export const updateTaskRow = async (taskId, rowId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        row: rowId === null ? null : {
          id: rowId
        }
      })
    });
    
    if (!response.ok) {
      try {
        const errorData = await response.json();
        console.error("Error details:", errorData);
        if (errorData.message === "Row not found" && response.status === 404) {
          throw new Error(`Error updating task row: ${response.status} - Row not found`);
        } else {
          throw new Error(`Error updating task row: ${response.status} - ${errorData.message || 'Unknown error'}`);
        }
      } catch (parseError) {
        console.warn("Failed to parse error response:", parseError.message);
        
        if (response.status === 404) {
          throw new Error(`Error updating task row: ${response.status} - Row not found`, { cause: parseError });
        } else {
          throw new Error(`Error updating task row: ${response.status} - Unknown error`, { cause: parseError });
        }
      }
    }

    return await response.json();
  } catch (error) {
    console.error(`Error updating task ${taskId} row:`, error);
    throw error;
  }
};

export const updateTaskName = async (id, name) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${id}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ title: name }),
    });
    
    if (!response.ok) {
      throw new Error(`Failed to update task: ${response.status}`);
    }
    
    const contentType = response.headers?.get?.('content-type');
    if (contentType && contentType.includes('application/json')) {
      return await response.json();
    } else {
      try {
        return await response.json();
      } catch (e) {
        console.warn('Failed to parse JSON response:', e.message);
        await response.text();
        return await fetchTask(id);
      }
    }
  } catch (error) {
    console.error('Error updating task name:', error);
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

export const fetchRows = async (retries = 3) => {
  while (retries > 0) {
    try {
      const response = await fetch(onActiveBoard(API_ENDPOINTS.ROWS));
      if (!response.ok) {
        throw new Error(`Error fetching rows: ${response.status}`);
      }
      return await response.json();
    } catch (error) {
      console.error('Error fetching rows:', error);
      if (retries === 1) throw error;
      retries--;
      await new Promise(resolve => setTimeout(resolve, 1000)); 
    }
  }
};

export const addRow = async (name, wipLimit) => {
  try {
    const response = await fetch(onActiveBoard(API_ENDPOINTS.ROWS), {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        name,
        wipLimit: parseInt(wipLimit) || 0
      })
    });
    
    if (!response.ok) {
      throw new Error(`Error adding row: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error('Error adding row:', error);
    throw error;
  }
};

export const updateRowWipLimit = async (rowId, wipLimit) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.ROWS}/${rowId}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        wipLimit: parseInt(wipLimit)
      })
    });

    if (response.status >= 200 && response.status < 300) {
      try {
        return await response.json();
      } catch (parseError) {
        console.warn('JSON parse error but update likely succeeded:', parseError);
        return {
          id: rowId,
          wipLimit: parseInt(wipLimit)
        };
      }
    }

    throw new Error(`Error updating row WIP limit: ${response.status}`);
  } catch (error) {
    console.error(`Error updating WIP limit for row ${rowId}:`, error);
    throw error;
  }
};

export const updateRowPosition = async (rowId, position) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.ROWS}/${rowId}/position/${position}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw new Error(`Error updating row position: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error updating position for row ${rowId}:`, error);
    throw error;
  }
};

export const deleteRow = async (rowId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.ROWS}/${rowId}`, {
      method: 'DELETE'
    });
    
    if (!response.ok && response.status !== 404) {
      throw new Error(`Error deleting row: ${response.status}`);
    }
    
    return true;
  } catch (error) {
    console.error(`Error deleting row ${rowId}:`, error);
    throw error;
  }
};

export const updateRowName = async (id, name) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.ROWS}/${id}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ name }),
    });
    
    if (!response.ok) {
      throw new Error(`Failed to update row: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error('Error updating row name:', error);
    throw error;
  }
};

export const fetchUsers = async () => {
  try {
    const response = await fetch(API_ENDPOINTS.USERS);
    if (!response.ok) {
      throw new Error(`Error fetching users: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error('Error fetching users:', error);
    throw error;
  }
};

export const fetchUser = async (userId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.USERS}/${userId}`);
    if (!response.ok) {
      throw new Error(`Error fetching user: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error fetching user ${userId}:`, error);
    throw error;
  }
};

export const deleteUser = async (userId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.USERS}/${userId}`, {
      method: 'DELETE'
    });
    
    if (!response.ok && response.status !== 404) {
      throw new Error(`Error deleting user: ${response.status}`);
    }
    
    return true;
  } catch (error) {
    console.error(`Error deleting user ${userId}:`, error);
    throw error;
  }
};

export const uploadUserAvatar = async (userId, file) => {
  try {
    const formData = new FormData();
    formData.append('file', file);
    
    const response = await fetch(`${API_ENDPOINTS.USERS}/${userId}/avatar`, {
      method: 'POST',
      body: formData
    });
    
    if (!response.ok) {
      throw new Error(`Error uploading avatar: ${response.status}`);
    }
    
    return await response.text();
  } catch (error) {
    console.error(`Error uploading avatar for user ${userId}:`, error);
    throw error;
  }
};

export const getUserAvatar = async (userId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.USERS}/${userId}/avatar`, {
      headers: {
        'Accept': 'image/*, application/json',
        'Cache-Control': 'no-cache'
      }
    });
    
    if (!response.ok) {
      console.warn(`Could not fetch avatar for user ${userId}: ${response.status}`);
      return null;
    }
    
    const blob = await response.blob();
    return URL.createObjectURL(blob);
  } catch (error) {
    console.warn(`Error fetching avatar for user ${userId}:`, error);
    return null;
  }
};

export const deleteUserAvatar = async (userId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.USERS}/${userId}/avatar`, {
      method: 'DELETE'
    });
    
    if (!response.ok) {
      throw new Error(`Error deleting avatar: ${response.status}`);
    }
    
    return await response.text();
  } catch (error) {
    console.error(`Error deleting avatar for user ${userId}:`, error);
    throw error;
  }
};

export const assignParentTask = async (childTaskId, parentTaskId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${childTaskId}/parent/${parentTaskId}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw new Error(`Error assigning parent task: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error assigning parent task for ${childTaskId}:`, error);
    throw error;
  }
};

export const removeParentTask = async (childTaskId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${childTaskId}/parent`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw new Error(`Error removing parent task: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error removing parent task for ${childTaskId}:`, error);
    throw error;
  }
};

export const getChildTasks = async (taskId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}/children`);
    if (!response.ok) {
      throw new Error(`Error fetching child tasks: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error fetching child tasks for ${taskId}:`, error);
    throw error;
  }
};

export const canTaskBeCompleted = async (taskId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}/can-complete`);
    if (!response.ok) {
      throw new Error(`Error checking if task can be completed: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error checking if task ${taskId} can be completed:`, error);
    throw error;
  }
};

export class ParentTaskNotCompletedError extends Error {
  constructor(taskId) {
    super(`Task ${taskId} has a parent that is still open`);
    this.name = 'ParentTaskNotCompletedError';
    this.taskId = taskId;
  }
}

export const updateTaskCompletion = async (taskId, completed) => {
  const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}/complete/${completed}`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json'
    }
  });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));

    if (response.status === 400 && errorData.code === 'PARENT_TASK_NOT_COMPLETED') {
      throw new ParentTaskNotCompletedError(taskId);
    }

    throw new Error(`${response.status}: Failed to update completion for task ${taskId}`);
  }

  return await response.json();
};

export const fetchDailyFocusTasks = async () => {
  try {
    const response = await fetch(onActiveBoard(`${API_ENDPOINTS.TASKS}/daily-focus`));
    if (!response.ok) {
      throw new Error(`Error fetching daily focus tasks: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error('Error fetching daily focus tasks:', error);
    throw error;
  }
};

export const setTaskDailyFocus = async (taskId, dailyFocus) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}/daily-focus/${dailyFocus}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      }
    });

    if (!response.ok) {
      throw new Error(`Error updating daily focus: ${response.status}`);
    }

    return await response.json();
  } catch (error) {
    console.error(`Error updating daily focus for task ${taskId}:`, error);
    throw error;
  }
};

export const getTaskColumnHistory = async (taskId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.TASKS}/${taskId}/column-history`);
    if (!response.ok) {
      throw new Error(`Error fetching task column history: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error fetching column history for task ${taskId}:`, error);
    throw error;
  }
};

export const fetchSubTask = async (subTaskId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.SUBTASKS}/${subTaskId}`);
    if (!response.ok) {
      throw new Error(`Error fetching subtask: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error fetching subtask ${subTaskId}:`, error);
    throw error;
  }
};

export const fetchSubTasksByTaskId = async (taskId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.SUBTASKS}/task/${taskId}`);
    if (!response.ok) {
      throw new Error(`Error fetching subtasks for task: ${response.status}`);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error fetching subtasks for task ${taskId}:`, error);
    throw error;
  }
};

export const addSubTask = async (taskId, title) => {
  try {
    const response = await fetch(API_ENDPOINTS.SUBTASKS, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        title,
        completed: false,
        task: {
          id: taskId
        }
      })
    });
    
    if (!response.ok) {
      throw new Error(`Error adding subtask: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error('Error adding subtask:', error);
    throw error;
  }
};

export const updateSubTask = async (subTaskId, subTaskData) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.SUBTASKS}/${subTaskId}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(subTaskData)
    });
    
    if (!response.ok) {
      throw new Error(`Error updating subtask: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error updating subtask ${subTaskId}:`, error);
    throw error;
  }
};

export const toggleSubTaskCompletion = async (subTaskId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.SUBTASKS}/${subTaskId}/change`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw new Error(`Error toggling subtask completion: ${response.status}`);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error toggling completion for subtask ${subTaskId}:`, error);
    throw error;
  }
};

export const deleteSubTask = async (subTaskId) => {
  try {
    const response = await fetch(`${API_ENDPOINTS.SUBTASKS}/${subTaskId}`, {
      method: 'DELETE'
    });
    
    if (!response.ok && response.status !== 404) {
      throw new Error(`Error deleting subtask: ${response.status}`);
    }
    
    return true;
  } catch (error) {
    console.error(`Error deleting subtask ${subTaskId}:`, error);
    throw error;
  }
};

const attachmentsOf = (taskId) => `${API_ENDPOINTS.TASKS}/${taskId}/attachments`;

export const MAX_ATTACHMENT_SIZE = 10 * 1024 * 1024;

export class AttachmentUploadError extends Error {
  constructor(reason, status) {
    super(`Attachment upload failed: ${reason}`);
    this.name = 'AttachmentUploadError';
    this.reason = reason;
    this.status = status;
  }
}

export const fetchTaskAttachments = async (taskId) => {
  const response = await fetch(attachmentsOf(taskId));

  if (!response.ok) {
    throw new Error(`Error fetching attachments: ${response.status}`);
  }

  return await response.json();
};

export const uploadTaskAttachment = async (taskId, file) => {
  if (file.size > MAX_ATTACHMENT_SIZE) {
    throw new AttachmentUploadError('tooLarge', 413);
  }

  const formData = new FormData();
  formData.append('file', file);

  const response = await fetch(attachmentsOf(taskId), {
    method: 'POST',
    body: formData
  });

  if (!response.ok) {
    const body = await response.json().catch(() => ({}));

    if (response.status === 413 || body.code === 'ATTACHMENT_TOO_LARGE') {
      throw new AttachmentUploadError('tooLarge', response.status);
    }
    if (response.status === 503 || body.code === 'ATTACHMENT_STORAGE_UNAVAILABLE') {
      throw new AttachmentUploadError('storageUnavailable', response.status);
    }
    throw new AttachmentUploadError('failed', response.status);
  }

  return await response.json();
};

const DOWNLOAD_RESUME_ATTEMPTS = 3;

const collectInto = async (url, state) => {
  const response = state.received > 0
    ? await fetch(url, { headers: { Range: `bytes=${state.received}-` } })
    : await fetch(url);

  if (!response.ok) {
    const error = new Error(`Error downloading the attachment: ${response.status}`);
    error.fatal = true;
    throw error;
  }

  if (state.received > 0 && response.status !== 206) {
    state.chunks = [];
    state.received = 0;
  }

  if (!response.body || typeof response.body.getReader !== 'function') {
    const whole = await response.blob();
    state.chunks.push(whole);
    state.received += whole.size;
    return;
  }

  const reader = response.body.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) {
      return;
    }
    state.chunks.push(value);
    state.received += value.byteLength;
  }
};

export const downloadTaskAttachment = async (taskId, attachmentId, fileName) => {
  const url = `${attachmentsOf(taskId)}/${attachmentId}/content`;
  const state = { chunks: [], received: 0 };

  for (let attempt = 0; ; attempt++) {
    try {
      await collectInto(url, state);
      break;
    } catch (error) {
      if (error.fatal || state.received === 0 || attempt >= DOWNLOAD_RESUME_ATTEMPTS) {
        throw error;
      }
    }
  }

  const blob = state.chunks.length === 1 && state.chunks[0] instanceof Blob
    ? state.chunks[0]
    : new Blob(state.chunks);
  const objectUrl = URL.createObjectURL(blob);

  const link = document.createElement('a');
  link.href = objectUrl;
  link.download = fileName || 'attachment';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(objectUrl);

  return true;
};

export const deleteTaskAttachment = async (taskId, attachmentId) => {
  const response = await fetch(`${attachmentsOf(taskId)}/${attachmentId}`, {
    method: 'DELETE'
  });

  if (!response.ok && response.status !== 404) {
    throw new Error(`Error deleting attachment: ${response.status}`);
  }

  return true;
};

const commentsOf = (taskId) => `${API_ENDPOINTS.TASKS}/${taskId}/comments`;

export const MAX_COMMENT_LENGTH = 2000;

export const COMMENT_PAGE_SIZE = 25;

export const fetchTaskComments = async (taskId, { page = 0, size = COMMENT_PAGE_SIZE } = {}) => {
  const params = new URLSearchParams();
  params.set('page', String(page));
  params.set('size', String(size));

  const response = await fetch(`${commentsOf(taskId)}?${params.toString()}`);
  if (!response.ok) {
    throw new Error(`Error fetching comments: ${response.status}`);
  }
  return response.json();
};

export const addTaskComment = async (taskId, body) => {
  const response = await fetch(commentsOf(taskId), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body })
  });
  if (!response.ok) {
    throw new Error(`Error adding comment: ${response.status}`);
  }
  return response.json();
};

export const editTaskComment = async (taskId, commentId, body) => {
  const response = await fetch(`${commentsOf(taskId)}/${commentId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ body })
  });
  if (!response.ok) {
    throw new Error(`Error editing comment: ${response.status}`);
  }
  return response.json();
};

export const deleteTaskComment = async (taskId, commentId) => {
  const response = await fetch(`${commentsOf(taskId)}/${commentId}`, {
    method: 'DELETE'
  });
  if (!response.ok && response.status !== 404) {
    throw new Error(`Error deleting comment: ${response.status}`);
  }
  return true;
};
