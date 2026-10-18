import { getJson, queryString } from './http';
import type { Id, PageRequest, TaskActivityResults } from '../types/api';

const ACTIVITY = '/api/activity';

export const DEFAULT_PAGE_SIZE = 25;

export const fetchActivity = ({ boardId, page = 0, size = DEFAULT_PAGE_SIZE }: PageRequest & { boardId?: Id | null } = {}) =>
  getJson<TaskActivityResults>(`${ACTIVITY}?${queryString({ boardId, page, size })}`);
