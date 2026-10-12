import { apiError, errorFromBody } from './apiError';

const TASKS = '/api/tasks';

export const assignParentTask = async (childTaskId, parentTaskId) => {
  try {
    const response = await fetch(`${TASKS}/${childTaskId}/parent/${parentTaskId}`, {
      method: 'PUT',
      headers: {
        'Content-Type': 'application/json'
      }
    });
    
    if (!response.ok) {
      throw await apiError(response);
    }
    
    return await response.json();
  } catch (error) {
    console.error(`Error assigning parent task for ${childTaskId}:`, error);
    throw error;
  }
};

export const removeParentTask = async (childTaskId) => {
  try {
    const response = await fetch(`${TASKS}/${childTaskId}/parent`, {
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
    console.error(`Error removing parent task for ${childTaskId}:`, error);
    throw error;
  }
};

export const getChildTasks = async (taskId) => {
  try {
    const response = await fetch(`${TASKS}/${taskId}/children`);
    if (!response.ok) {
      throw await apiError(response);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error fetching child tasks for ${taskId}:`, error);
    throw error;
  }
};

export const canTaskBeCompleted = async (taskId) => {
  try {
    const response = await fetch(`${TASKS}/${taskId}/can-complete`);
    if (!response.ok) {
      throw await apiError(response);
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
  const response = await fetch(`${TASKS}/${taskId}/complete/${completed}`, {
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

    throw errorFromBody(response.status, errorData);
  }

  return await response.json();
};
