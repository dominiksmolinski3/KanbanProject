import { deleteQuietly, getJson, patchJson, postJson, queryString } from './http';
import type { Id, PageRequest, TaskComment, TaskCommentResults } from '../types/api';

const TASKS = '/api/tasks';

const commentsOf = (taskId: Id) => `${TASKS}/${taskId}/comments`;

export const MAX_COMMENT_LENGTH = 2000;

export const COMMENT_PAGE_SIZE = 25;

export const fetchTaskComments = (taskId: Id, { page = 0, size = COMMENT_PAGE_SIZE }: PageRequest = {}) =>
  getJson<TaskCommentResults>(`${commentsOf(taskId)}?${queryString({ page, size })}`);

export const addTaskComment = (taskId: Id, body: string) => postJson<TaskComment>(commentsOf(taskId), { body });

export const editTaskComment = (taskId: Id, commentId: number, body: string) =>
  patchJson<TaskComment>(`${commentsOf(taskId)}/${commentId}`, { body });

export const deleteTaskComment = (taskId: Id, commentId: number) =>
  deleteQuietly(`${commentsOf(taskId)}/${commentId}`);
