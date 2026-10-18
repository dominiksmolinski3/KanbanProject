import { useCallback, useState } from 'react';
import type { Id, Task } from '../types/api';
import type { LooseId, MoveTask } from './kanban/types';

export type Direction = 'left' | 'right' | 'up' | 'down';

export interface Cell {
  columnId: LooseId;
  rowId: LooseId;
}

interface Held {
  taskId: Id;
  title: string;
  from: Cell;
  cell: Cell;
}

interface Announcement {
  key: string;
  values: Record<string, string>;
}

interface Named {
  id: LooseId;
  name: string;
}

const STEPS: Record<string, { columns: number; rows: number }> = {
  left: { columns: -1, rows: 0 },
  right: { columns: 1, rows: 0 },
  up: { columns: 0, rows: -1 },
  down: { columns: 0, rows: 1 }
};

export const neighbourOf = (items: { id: LooseId }[], id: LooseId, offset: number): LooseId => {
  const index = items.findIndex(item => String(item.id) === String(id));
  if (index < 0) {
    return id;
  }
  const next = Math.min(items.length - 1, Math.max(0, index + offset));
  return items[next].id;
};

export const stepCell = (
  { columns, rows, cell, direction }:
  { columns: { id: LooseId }[]; rows: { id: LooseId }[]; cell: Cell; direction: string }
): Cell => {
  const step = STEPS[direction];
  if (!step) {
    return cell;
  }
  return {
    columnId: step.columns === 0 ? cell.columnId : neighbourOf(columns, cell.columnId, step.columns),
    rowId: step.rows === 0 ? cell.rowId : neighbourOf(rows, cell.rowId, step.rows)
  };
};

export const sameCell = (one: Cell, other: Cell): boolean =>
  String(one.columnId) === String(other.columnId) && String(one.rowId) === String(other.rowId);

const nameOf = (items: Named[], id: LooseId): string => {
  const found = items.find(item => String(item.id) === String(id));
  return found ? found.name : '';
};

export function useKeyboardMove(
  { columns, rows, moveTask }: { columns: Named[]; rows: Named[]; moveTask: MoveTask }
) {
  const [held, setHeld] = useState<Held | null>(null);

  const [announcement, setAnnouncement] = useState<Announcement | null>(null);

  const announce = useCallback((key: string, values: Record<string, string>) => setAnnouncement({ key, values }), []);

  const grab = useCallback((task: Pick<Task, 'id' | 'title'> | null | undefined, columnId?: LooseId | null, rowId?: LooseId | null) => {
    if (!task || columnId === undefined || columnId === null || rowId === undefined || rowId === null) {
      return;
    }
    setHeld({
      taskId: task.id,
      title: task.title,
      from: { columnId, rowId },
      cell: { columnId, rowId }
    });
    announce('board.keyboardMove.grabbed', { title: task.title });
  }, [announce]);

  const step = useCallback((direction: Direction) => {
    setHeld(current => {
      if (!current) {
        return current;
      }
      const cell = stepCell({ columns, rows, cell: current.cell, direction });
      if (sameCell(cell, current.cell)) {
        return current;
      }
      announce('board.keyboardMove.over', {
        column: nameOf(columns, cell.columnId),
        row: nameOf(rows, cell.rowId)
      });
      return { ...current, cell };
    });
  }, [columns, rows, announce]);

  const cancel = useCallback(() => {
    setHeld(current => {
      if (current) {
        announce('board.keyboardMove.cancelled', { title: current.title });
      }
      return null;
    });
  }, [announce]);

  const drop = useCallback(() => {
    setHeld(current => {
      if (!current) {
        return null;
      }
      if (sameCell(current.cell, current.from)) {
        announce('board.keyboardMove.unchanged', { title: current.title });
        return null;
      }
      announce('board.keyboardMove.dropped', {
        title: current.title,
        column: nameOf(columns, current.cell.columnId),
        row: nameOf(rows, current.cell.rowId)
      });
      Promise.resolve().then(() => moveTask(current.taskId, current.cell.columnId, current.cell.rowId));
      return null;
    });
  }, [columns, rows, moveTask, announce]);

  const isHeld = useCallback(
    (taskId: LooseId) => Boolean(held) && String(held!.taskId) === String(taskId),
    [held]);

  const isTarget = useCallback(
    (columnId: LooseId, rowId: LooseId) => Boolean(held) && sameCell(held!.cell, { columnId, rowId }),
    [held]);

  return { held, announcement, grab, step, drop, cancel, isHeld, isTarget };
}
