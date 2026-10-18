import { getJson, putJson, queryString } from './http';
import type { FlowDefinition, FlowMetrics, Id } from '../types/api';

const FLOW = '/api/flow';

export const MAX_FLOW_DAYS = 180;

interface FlowQuery {
  boardId?: Id | null;
  from?: string;
  to?: string;
  start?: Id | string | null;
  done?: Id | string | null;
}

const unlessBlank = <T>(value: T) => (value === '' ? undefined : value);

export const fetchFlowMetrics = ({ boardId, from, to, start, done }: FlowQuery = {}) =>
  getJson<FlowMetrics>(`${FLOW}?${queryString({
    boardId: unlessBlank(boardId),
    from: unlessBlank(from),
    to: unlessBlank(to),
    start: unlessBlank(start),
    done: unlessBlank(done)
  })}`);

export const defineFlow = ({ boardId, start = null, done = null }: { boardId?: Id | null; start?: Id | null; done?: Id | null } = {}) =>
  putJson<FlowDefinition>(`${FLOW}/definition?${queryString({ boardId })}`, { startColumnId: start, doneColumnId: done });
