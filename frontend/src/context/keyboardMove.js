import { useCallback, useState } from 'react';

/**
 * HTML5 drag-and-drop has no keyboard equivalent, so a card that can only be dragged is unusable
 * without a pointer; this follows the ARIA authoring-practice pattern instead (Space picks up,
 * arrows choose a cell, Space/Enter drops, Escape cancels). Nothing reaches the server until the
 * drop, or crossing four columns would fire four requests, four toasts and four activity rows; the
 * card stays in its cell while held so its element never unmounts and focus isn't lost.
 */

/** The four directions an arrow key can mean, as offsets into the column and row lists. */
const STEPS = {
  left: { columns: -1, rows: 0 },
  right: { columns: 1, rows: 0 },
  up: { columns: 0, rows: -1 },
  down: { columns: 0, rows: 1 }
};

/**
 * The neighbour of `id` in `items`, clamped rather than wrapped: wrapping would let one more
 * keypress move a card the length of the board, which looks like a small adjustment to someone
 * who may not be watching the screen.
 */
export const neighbourOf = (items, id, offset) => {
  const index = items.findIndex(item => String(item.id) === String(id));
  if (index < 0) {
    return id;
  }
  const next = Math.min(items.length - 1, Math.max(0, index + offset));
  return items[next].id;
};

/**
 * The cell a step lands on, given the ordered columns and rows the board renders.
 *
 * Pure, and exported for the tests: the arithmetic is the part worth checking, and checking it
 * through a rendered board would mean asserting it through two components and a context.
 */
export const stepCell = ({ columns, rows, cell, direction }) => {
  const step = STEPS[direction];
  if (!step) {
    return cell;
  }
  return {
    columnId: step.columns === 0 ? cell.columnId : neighbourOf(columns, cell.columnId, step.columns),
    rowId: step.rows === 0 ? cell.rowId : neighbourOf(rows, cell.rowId, step.rows)
  };
};

/** Same cell, comparing as strings because ids arrive from both the DOM and the API. */
export const sameCell = (one, other) =>
  String(one.columnId) === String(other.columnId) && String(one.rowId) === String(other.rowId);

const nameOf = (items, id) => {
  const found = items.find(item => String(item.id) === String(id));
  return found ? found.name : '';
};

export function useKeyboardMove({ columns, rows, moveTask }) {
  const [held, setHeld] = useState(null);

  /**
   * A key and its values rather than a sentence, the same rule the activity feed follows: a
   * message composed here is one the other eight languages cannot translate. Board.jsx renders it
   * through `t()` into the live region.
   */
  const [announcement, setAnnouncement] = useState(null);

  const announce = useCallback((key, values) => setAnnouncement({ key, values }), []);

  const grab = useCallback((task, columnId, rowId) => {
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

  const step = useCallback((direction) => {
    setHeld(current => {
      if (!current) {
        return current;
      }
      const cell = stepCell({ columns, rows, cell: current.cell, direction });
      if (sameCell(cell, current.cell)) {
        // Already at the edge. The announcement is left alone deliberately - repeating the cell
        // name would read as movement that did not happen.
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
      // Outside the state updater's own work, so a re-render that replays it cannot send the move
      // twice; moveTask is the same call the drop handler makes and reports its own failures.
      Promise.resolve().then(() => moveTask(current.taskId, current.cell.columnId, current.cell.rowId));
      return null;
    });
  }, [columns, rows, moveTask, announce]);

  const isHeld = useCallback(
    (taskId) => Boolean(held) && String(held.taskId) === String(taskId),
    [held]);

  const isTarget = useCallback(
    (columnId, rowId) => Boolean(held) && sameCell(held.cell, { columnId, rowId }),
    [held]);

  return { held, announcement, grab, step, drop, cancel, isHeld, isTarget };
}
