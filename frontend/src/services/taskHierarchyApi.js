import { errorFromBody } from './apiError';
import { read } from './http';

const TASKS = '/api/tasks';

export const assignParentTask = async (childTaskId, parentTaskId) => {
  const response = await fetch(`${TASKS}/${childTaskId}/parent/${parentTaskId}`, { method: 'PUT' });
  return read(response);
};

export const removeParentTask = async (childTaskId) => {
  const response = await fetch(`${TASKS}/${childTaskId}/parent`, { method: 'DELETE' });
  return read(response);
};

export const getChildTasks = async (taskId) => {
  const response = await fetch(`${TASKS}/${taskId}/children`);
  return read(response);
};

export const canTaskBeCompleted = async (taskId) => {
  const response = await fetch(`${TASKS}/${taskId}/can-complete`);
  return read(response);
};

export class ParentTaskNotCompletedError extends Error {
  constructor(taskId) {
    super(`Task ${taskId} has a parent that is still open`);
    this.name = 'ParentTaskNotCompletedError';
    this.taskId = taskId;
  }
}

export const updateTaskCompletion = async (taskId, completed) => {
  const response = await fetch(`${TASKS}/${taskId}/complete/${completed}`, { method: 'PATCH' });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));

    if (response.status === 400 && errorData.code === 'PARENT_TASK_NOT_COMPLETED') {
      throw new ParentTaskNotCompletedError(taskId);
    }

    throw errorFromBody(response.status, errorData);
  }

  return response.json();
};
