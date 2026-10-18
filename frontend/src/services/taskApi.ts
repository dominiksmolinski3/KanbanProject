import { onActiveBoard } from './activeBoard';
import { deleteJson, deleteQuietly, getJson, patchJson, postJson, read, readUnless } from './http';
import { reorder, ConcurrentModificationError } from './reorder';
import { getUserWipStatus } from './userApi';
import type { Id, Task, TaskPatch, WipStatus } from '../types/api';

const TASKS = '/api/tasks';

export const fetchTasks = () => getJson<Task[]>(onActiveBoard(TASKS));

export const fetchTask = (taskId: Id) => getJson<Task>(`${TASKS}/${taskId}`);

export const addTask = (title: string, columnId: Id, deadline: string | null = null) =>
  postJson<Task>(onActiveBoard(TASKS), { title, column: { id: columnId }, deadline });

export const updateTask = async (taskId: Id, taskData: TaskPatch) => {
  const response = await fetch(`${TASKS}/${taskId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(taskData)
  });
  if (response.status === 409) {
    throw new ConcurrentModificationError('task');
  }
  return read<Task>(response);
};

export const updateTaskColumn = (taskId: Id, columnId: Id) =>
  patchJson<Task>(`${TASKS}/${taskId}`, { column: { id: columnId } });

export const updateTaskPosition = (taskId: Id, position: number) =>
  patchJson<Task>(`${TASKS}/${taskId}/position/${position}`);

export const reorderTasks = (orderedIds: Id[]) => reorder<Task[]>(TASKS, orderedIds, 'task');

export class WipLimitExceededError extends Error {
  status: WipStatus | undefined;

  constructor(status?: WipStatus) {
    super(`WIP limit reached for user ${status?.userId}`);
    this.name = 'WipLimitExceededError';
    this.status = status;
  }
}

export const assignUserToTask = async (taskId: Id, userId: Id) => {
  const wipStatus = await getUserWipStatus(userId);

  if (!wipStatus.withinLimit) {
    throw new WipLimitExceededError(wipStatus);
  }

  const response = await fetch(`${TASKS}/${taskId}/user/${userId}`, { method: 'PUT' });
  return readUnless<Task>(response, 'USER_WIP_LIMIT_EXCEEDED', () => new WipLimitExceededError(wipStatus));
};

export const removeUserFromTask = (taskId: Id, userId: Id) => deleteJson<Task>(`${TASKS}/${taskId}/user/${userId}`);

export const updateTaskLabels = (taskId: Id, labels: string[]) => patchJson<Task>(`${TASKS}/${taskId}/labels`, labels);

export const deleteTask = (taskId: Id) => deleteQuietly(`${TASKS}/${taskId}`);

export const updateTaskRow = (taskId: Id, rowId: Id | null) =>
  patchJson<Task>(`${TASKS}/${taskId}`, { row: rowId === null ? null : { id: rowId } });

export const updateTaskName = (id: Id, name: string) => patchJson<Task>(`${TASKS}/${id}`, { title: name });

export const fetchDailyFocusTasks = () => getJson<Task[]>(onActiveBoard(`${TASKS}/daily-focus`));

export const setTaskDailyFocus = (taskId: Id, dailyFocus: boolean) =>
  patchJson<Task>(`${TASKS}/${taskId}/daily-focus/${dailyFocus}`);
