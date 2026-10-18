import type { Id, Task } from '../types/api';

export const WIP_NEAR_RATIO = 0.8;

export type WipState = 'none' | 'over' | 'near' | 'ok';

export interface WipSubject {
  id: Id;
  wipLimit?: number | null;
}

export interface WipDecoration {
  taskCount: number;
  wipState: WipState;
  isOverLimit: boolean;
  wipFill: number;
}

type PlacedTask = Pick<Task, 'columnId' | 'rowId' | 'dailyFocus'>;

export function wipState(count: number, limit?: number | null): WipState {
  if (!limit || limit <= 0) return 'none';
  if (count > limit) return 'over';
  if (count >= limit * WIP_NEAR_RATIO) return 'near';
  return 'ok';
}

export function wipFill(count: number, limit?: number | null): number {
  if (!limit || limit <= 0) return 0;
  return Math.min(100, Math.round((count / limit) * 100));
}

const cellKey = (columnId: Id, rowId: Id | null | undefined) => `${columnId}::${rowId ?? ''}`;

function withWip<T extends WipSubject>(item: T, count: number): T & WipDecoration {
  const state = wipState(count, item.wipLimit);
  return {
    ...item,
    taskCount: count,
    wipState: state,
    isOverLimit: state === 'over',
    wipFill: wipFill(count, item.wipLimit),
  };
}

interface BoardModelInput<C extends WipSubject, R extends WipSubject, T extends PlacedTask> {
  columns?: C[];
  rows?: R[];
  tasks?: T[];
  dailyFocusOnly?: boolean;
}

// WIP counts always cover every task; only the cells honour the daily-focus filter.
export function buildBoardModel<C extends WipSubject, R extends WipSubject, T extends PlacedTask>(
  { columns = [], rows = [], tasks = [], dailyFocusOnly = false }: BoardModelInput<C, R, T>
) {
  const columnCounts = new Map<Id, number>();
  const rowCounts = new Map<Id | null, number>();
  const cells = new Map<string, T[]>();
  let dailyFocusCount = 0;

  for (const task of tasks) {
    columnCounts.set(task.columnId, (columnCounts.get(task.columnId) || 0) + 1);
    rowCounts.set(task.rowId, (rowCounts.get(task.rowId) || 0) + 1);
    if (task.dailyFocus) dailyFocusCount += 1;
    if (dailyFocusOnly && !task.dailyFocus) continue;
    const key = cellKey(task.columnId, task.rowId);
    const cell = cells.get(key);
    if (cell) cell.push(task);
    else cells.set(key, [task]);
  }

  const enhancedColumns = columns.map((column) => withWip(column, columnCounts.get(column.id) || 0));
  const enhancedRows = rows.map((row) => withWip(row, rowCounts.get(row.id) || 0));

  return {
    columns: enhancedColumns,
    rows: enhancedRows,
    dailyFocusCount,
    tasksIn: (columnId: Id, rowId: Id | null = null): T[] => cells.get(cellKey(columnId, rowId)) || [],
  };
}
