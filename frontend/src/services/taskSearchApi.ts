import { onActiveBoard } from './activeBoard';
import { getJson } from './http';
import type { Id, PageRequest, TaskSearchResults } from '../types/api';

const TASKS = '/api/tasks';

export const getAllLabels = () => getJson<string[]>(onActiveBoard(`${TASKS}/get/all/labels`));

export const MAX_SEARCH_PAGE_SIZE = 100;

export const SEARCH_PAGE_SIZE = 25;

export interface TaskSearchFilters extends PageRequest {
  q?: string;
  labels?: string[];
  assignees?: Id[];
  completed?: boolean | null;
  deadlineFrom?: string | null;
  deadlineTo?: string | null;
}

export const searchTasks = async (filters: TaskSearchFilters = {}) => {
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

  return getJson<TaskSearchResults>(onActiveBoard(`${TASKS}/search?${params.toString()}`));
};
