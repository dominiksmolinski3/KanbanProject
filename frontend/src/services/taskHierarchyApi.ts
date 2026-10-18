import { deleteJson, getJson, putJson, readUnless } from './http';
import type { Id, Task } from '../types/api';

const TASKS = '/api/tasks';

export const assignParentTask = (childTaskId: Id, parentTaskId: Id) =>
  putJson<Task>(`${TASKS}/${childTaskId}/parent/${parentTaskId}`);

export const removeParentTask = (childTaskId: Id) => deleteJson<Task>(`${TASKS}/${childTaskId}/parent`);

export const getChildTasks = (taskId: Id) => getJson<Task[]>(`${TASKS}/${taskId}/children`);

export const canTaskBeCompleted = (taskId: Id) => getJson<boolean>(`${TASKS}/${taskId}/can-complete`);

export class ParentTaskNotCompletedError extends Error {
  taskId: Id;

  constructor(taskId: Id) {
    super(`Task ${taskId} has a parent that is still open`);
    this.name = 'ParentTaskNotCompletedError';
    this.taskId = taskId;
  }
}

export const updateTaskCompletion = async (taskId: Id, completed: boolean) => {
  const response = await fetch(`${TASKS}/${taskId}/complete/${completed}`, { method: 'PATCH' });
  return readUnless<Task>(response, 'PARENT_TASK_NOT_COMPLETED', () => new ParentTaskNotCompletedError(taskId));
};
