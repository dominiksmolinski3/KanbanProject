import { errorFromBody } from './apiError';
import { onActiveBoard } from './activeBoard';
import { read, readDeleted } from './http';
import { reorder, ConcurrentModificationError } from './reorder';
import { getUserWipStatus } from './userApi';

const TASKS = '/api/tasks';

export const fetchTasks = async () => {
  const response = await fetch(onActiveBoard(TASKS));
  return read(response);
};

export const fetchTask = async (taskId) => {
  const response = await fetch(`${TASKS}/${taskId}`);
  return read(response);
};

export const addTask = async (title, columnId, deadline = null) => {
  const response = await fetch(onActiveBoard(TASKS), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title, column: { id: columnId }, deadline })
  });
  return read(response);
};

export const updateTask = async (taskId, taskData) => {
  const response = await fetch(`${TASKS}/${taskId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(taskData)
  });
  if (response.status === 409) {
    throw new ConcurrentModificationError('task');
  }
  return read(response);
};

export const updateTaskColumn = async (taskId, columnId) => {
  const response = await fetch(`${TASKS}/${taskId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ column: { id: columnId } })
  });
  return read(response);
};

export const updateTaskPosition = async (taskId, position) => {
  const response = await fetch(`${TASKS}/${taskId}/position/${position}`, { method: 'PATCH' });
  return read(response);
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

  const response = await fetch(`${TASKS}/${taskId}/user/${userId}`, { method: 'PUT' });

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));

    if (response.status === 400 && errorData.code === 'USER_WIP_LIMIT_EXCEEDED') {
      throw new WipLimitExceededError(wipStatus);
    }

    throw errorFromBody(response.status, errorData);
  }

  return response.json();
};

export const removeUserFromTask = async (taskId, userId) => {
  const response = await fetch(`${TASKS}/${taskId}/user/${userId}`, { method: 'DELETE' });
  return read(response);
};

export const updateTaskLabels = async (taskId, labels) => {
  const response = await fetch(`${TASKS}/${taskId}/labels`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(labels)
  });
  return read(response);
};

export const deleteTask = async (taskId) => {
  const response = await fetch(`${TASKS}/${taskId}`, { method: 'DELETE' });
  return readDeleted(response);
};

export const updateTaskRow = async (taskId, rowId) => {
  const response = await fetch(`${TASKS}/${taskId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ row: rowId === null ? null : { id: rowId } })
  });
  return read(response);
};

export const updateTaskName = async (id, name) => {
  const response = await fetch(`${TASKS}/${id}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title: name })
  });
  return read(response);
};

export const fetchDailyFocusTasks = async () => {
  const response = await fetch(onActiveBoard(`${TASKS}/daily-focus`));
  return read(response);
};

export const setTaskDailyFocus = async (taskId, dailyFocus) => {
  const response = await fetch(`${TASKS}/${taskId}/daily-focus/${dailyFocus}`, { method: 'PATCH' });
  return read(response);
};
