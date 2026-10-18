import { deleteQuietly, getJson, patchJson, postJson } from './http';
import type { Id, SubTask } from '../types/api';

const SUBTASKS = '/api/subtasks';

export const fetchSubTask = (subTaskId: Id) => getJson<SubTask>(`${SUBTASKS}/${subTaskId}`);

export const fetchSubTasksByTaskId = (taskId: Id) => getJson<SubTask[]>(`${SUBTASKS}/task/${taskId}`);

export const addSubTask = (taskId: Id, title: string) =>
  postJson<SubTask>(SUBTASKS, { title, completed: false, task: { id: taskId } });

export const updateSubTask = (subTaskId: Id, subTaskData: Partial<Omit<SubTask, 'id' | 'taskId'>>) =>
  patchJson<SubTask>(`${SUBTASKS}/${subTaskId}`, subTaskData);

export const toggleSubTaskCompletion = (subTaskId: Id) => patchJson<SubTask>(`${SUBTASKS}/${subTaskId}/change`);

export const deleteSubTask = (subTaskId: Id) => deleteQuietly(`${SUBTASKS}/${subTaskId}`);
