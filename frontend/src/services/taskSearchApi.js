import { apiError } from './apiError';
import { onActiveBoard } from './activeBoard';

const TASKS = '/api/tasks';

export const getAllLabels = async () => {
  try {
    const response = await fetch(onActiveBoard(`${TASKS}/get/all/labels`));
    if (!response.ok) {
      throw await apiError(response);
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
    onActiveBoard(`${TASKS}/search?${params.toString()}`)
  );

  if (!response.ok) {
    throw await apiError(response);
  }

  return await response.json();
};
