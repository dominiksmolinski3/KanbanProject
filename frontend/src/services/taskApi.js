import { apiError, errorFromBody } from './apiError';
import { onActiveBoard } from './activeBoard';
import { reorder, ConcurrentModificationError } from './reorder';
import { getUserWipStatus } from './userApi';

const TASKS = '/api/tasks';

export const fetchTasks = async () => {
  try {
    const response = await fetch(onActiveBoard(TASKS));
    if (!response.ok) {
      throw await apiError(response);
    }
    return await response.json();
  } catch (error) {
    console.error('Error fetching tasks:', error);
    throw error;
  }
};

export const fetchTask = async (taskId) => {
  try {
    const response = await fetch(`${TASKS}/${taskId}`);
    if (!response.ok) {
      throw await apiError(response);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error fetching task ${taskId}:`, error);
    throw error;
  }
};

export const addTask = async (title, columnId, deadline = null) => {
  try {
    const response = await fetch(onActiveBoard(TASKS), {
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
      throw await apiError(response);
    }
    
    return await response.json();
  } catch (error) {
    console.error('Error adding task:', error);
    throw error;
  }
};

export const updateTask = async (taskId, taskData) => {
  try {
    const response = await fetch(`${TASKS}/${taskId}`, {
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
      throw await apiError(response);
    }

    return await response.json();
  } catch (error) {
    console.error(`Error updating task ${taskId}:`, error);
    throw error;
  }
};

export const updateTaskColumn = async (taskId, columnId) => {
  try {
    const response = await fetch(`${TASKS}/${taskId}`, {
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
      throw await apiError(response);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error updating task ${taskId}:`, error);
    throw error;
  }
};

export const updateTaskPosition = async (taskId, position) => {
  try {
    const response = await fetch(`${TASKS}/${taskId}/position/${position}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw await apiError(response);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error updating position for task ${taskId}:`, error);
    throw error;
  }
};

export const reorderTasks = (orderedIds) => reorder(TASKS, orderedIds, 'task');

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

  const response = await fetch(`${TASKS}/${taskId}/user/${userId}`, {
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

    throw errorFromBody(response.status, errorData);
  }

  return await response.json();
};

export const removeUserFromTask = async (taskId, userId) => {
  try {
    const response = await fetch(`${TASKS}/${taskId}/user/${userId}`, {
      method: 'DELETE',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw await apiError(response);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error removing user from task ${taskId}:`, error);
    throw error;
  }
};

export const updateTaskLabels = async (taskId, labels) => {
  try {
    const response = await fetch(`${TASKS}/${taskId}/labels`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(labels)
    });
    
    if (!response.ok) {
      throw await apiError(response);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error updating labels for task ${taskId}:`, error);
    throw error;
  }
};

export const deleteTask = async (taskId) => {
  try {
    const response = await fetch(`${TASKS}/${taskId}`, {
      method: 'DELETE'
    });
    
    if (!response.ok && response.status !== 404) {
      throw await apiError(response);
    }
    
    return true;
  } catch (error) {
    console.error(`Error deleting task ${taskId}:`, error);
    throw error;
  }
};

export const updateTaskRow = async (taskId, rowId) => {
  try {
    const response = await fetch(`${TASKS}/${taskId}`, {
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
      throw await apiError(response);
    }

    return await response.json();
  } catch (error) {
    console.error(`Error updating task ${taskId} row:`, error);
    throw error;
  }
};

export const updateTaskName = async (id, name) => {
  try {
    const response = await fetch(`${TASKS}/${id}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ title: name }),
    });
    
    if (!response.ok) {
      throw await apiError(response);
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

export const fetchDailyFocusTasks = async () => {
  try {
    const response = await fetch(onActiveBoard(`${TASKS}/daily-focus`));
    if (!response.ok) {
      throw await apiError(response);
    }
    return await response.json();
  } catch (error) {
    console.error('Error fetching daily focus tasks:', error);
    throw error;
  }
};

export const setTaskDailyFocus = async (taskId, dailyFocus) => {
  try {
    const response = await fetch(`${TASKS}/${taskId}/daily-focus/${dailyFocus}`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json'
      }
    });

    if (!response.ok) {
      throw await apiError(response);
    }

    return await response.json();
  } catch (error) {
    console.error(`Error updating daily focus for task ${taskId}:`, error);
    throw error;
  }
};
