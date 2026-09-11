import { useCallback, useState } from 'react';

/**
 * Moving a card without a pointer.
 *
 * The board's drag-and-drop is HTML5 DnD, which has no keyboard equivalent at all - there is no
 * key that starts a drag, and no amount of `tabIndex` produces one. So a card that can only be
 * moved by dragging cannot be moved by anybody who does not use a mouse, which on a Kanban board
 * is the one thing the board is for.
 *
 * This is the ARIA authoring-practice shape rather than a new one: Space picks a card up, the
 * arrow keys choose a cell, Space or Enter drops it there, Escape puts it back. The important part
 * is that **nothing happens until the drop**. Committing on each arrow press would be simpler and
 * would fire a request per keystroke, and a person crossing four columns would move their card
 * four times, each one a toast, a refresh and a row in the activity feed. The pending target lives
 * here; only `drop` calls the server, and only when the cell actually changed.
 *
 * The card is not re-parented while it is held, which is what keeps the keyboard focus on it: the
 * element never unmounts, so there is nothing to restore focus to afterwards.
 */

/** The four directions an arrow key can mean, as offsets into the column and row lists. */
const STEPS = {
  left: { columns: -1, rows: 0 },
  right: { columns: 1, rows: 0 },
  up: { columns: 0, rows: -1 },
  down: { columns: 0, rows: 1 }
};

/**
 * The neighbour of `id` in `items`, clamped at both ends.
 *
 * Clamped rather than wrapped: a card at the last column that jumps to the first on one more
 * press has moved the length of the board on a keystroke that looked like a small adjustment, and
 * the person holding it may not be looking at the screen at all.
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
   * What to say, as a key and its values rather than a sentence.
   *
   * The same rule the activity feed follows, and for the same reason: a message composed here is a
   * message the other eight languages cannot translate. Board.jsx renders this through `t()` into
   * the live region.
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
