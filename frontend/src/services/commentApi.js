import { apiError } from './apiError';

const TASKS = '/api/tasks';

const commentsOf = (taskId) => `${TASKS}/${taskId}/comments`;

export const MAX_COMMENT_LENGTH = 2000;

export const COMMENT_PAGE_SIZE = 25;

export const fetchTaskComments = async (taskId, { page = 0, size = COMMENT_PAGE_SIZE } = {}) => {
  const params = new URLSearchParams();
  params.set('page', String(page));
  params.set('size', String(size));

  const response = await fetch(`${commentsOf(taskId)}?${params.toString()}`);
  if (!response.ok) {
    throw await apiError(response);
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
    throw await apiError(response);
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
    throw await apiError(response);
  }
  return response.json();
};

export const deleteTaskComment = async (taskId, commentId) => {
  const response = await fetch(`${commentsOf(taskId)}/${commentId}`, {
    method: 'DELETE'
  });
  if (!response.ok && response.status !== 404) {
    throw await apiError(response);
  }
  return true;
};
