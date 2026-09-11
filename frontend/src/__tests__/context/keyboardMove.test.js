import { renderHook, act } from '@testing-library/react';
import { neighbourOf, stepCell, sameCell, useKeyboardMove } from '../../context/keyboardMove';

/**
 * The arithmetic and the state machine behind moving a card without a pointer.
 *
 * Kept off the rendered board on purpose: through a board these would be assertions about two
 * components and a context, and the thing worth checking is that the step clamps, that nothing
 * reaches the server until the drop, and that a drop onto the cell the card came from is not a
 * move at all.
 */

const columns = [
  { id: 'col1', name: 'To Do' },
  { id: 'col2', name: 'In Progress' },
  { id: 'col3', name: 'Done' }
];

const rows = [
  { id: 'row1', name: 'Features' },
  { id: 'row2', name: 'Bugs' }
];

const task = { id: 't1', title: 'Write the thing' };

const setUp = () => {
  const moveTask = jest.fn();
  const rendered = renderHook(() => useKeyboardMove({ columns, rows, moveTask }));
  return { moveTask, ...rendered };
};

describe('stepping between cells', () => {
  test('a step moves to the neighbour in that direction', () => {
    expect(stepCell({ columns, rows, cell: { columnId: 'col1', rowId: 'row1' }, direction: 'right' }))
      .toEqual({ columnId: 'col2', rowId: 'row1' });
    expect(stepCell({ columns, rows, cell: { columnId: 'col2', rowId: 'row1' }, direction: 'down' }))
      .toEqual({ columnId: 'col2', rowId: 'row2' });
  });

  test('a step at the edge stays put rather than wrapping round', () => {
    // Wrapping would move a card the width of the board on a keystroke that looked like a nudge,
    // to somebody who may not be looking at the screen at all.
    expect(stepCell({ columns, rows, cell: { columnId: 'col1', rowId: 'row1' }, direction: 'left' }))
      .toEqual({ columnId: 'col1', rowId: 'row1' });
    expect(stepCell({ columns, rows, cell: { columnId: 'col3', rowId: 'row2' }, direction: 'down' }))
      .toEqual({ columnId: 'col3', rowId: 'row2' });
  });

  test('ids are compared as strings, because they arrive from the DOM as well as the API', () => {
    expect(neighbourOf([{ id: 1 }, { id: 2 }], '1', 1)).toBe(2);
    expect(sameCell({ columnId: 1, rowId: 2 }, { columnId: '1', rowId: '2' })).toBe(true);
  });

  test('an unknown cell and an unknown direction change nothing', () => {
    expect(stepCell({ columns, rows, cell: { columnId: 'gone', rowId: 'row1' }, direction: 'right' }))
      .toEqual({ columnId: 'gone', rowId: 'row1' });
    expect(stepCell({ columns, rows, cell: { columnId: 'col1', rowId: 'row1' }, direction: 'sideways' }))
      .toEqual({ columnId: 'col1', rowId: 'row1' });
  });
});

describe('holding and dropping a card', () => {
  test('nothing is held until something is picked up', () => {
    const { result } = setUp();

    expect(result.current.held).toBeNull();
    expect(result.current.isHeld('t1')).toBe(false);
    expect(result.current.isTarget('col1', 'row1')).toBe(false);
  });

  test('picking a card up targets the cell it is already in', () => {
    const { result } = setUp();

    act(() => result.current.grab(task, 'col1', 'row1'));

    expect(result.current.isHeld('t1')).toBe(true);
    expect(result.current.isTarget('col1', 'row1')).toBe(true);
    expect(result.current.announcement).toEqual({
      key: 'board.keyboardMove.grabbed',
      values: { title: 'Write the thing' }
    });
  });

  test('stepping moves the target and calls nothing', () => {
    // The whole reason the target is held here rather than committed per keystroke: crossing four
    // columns would otherwise be four moves, four toasts, four refreshes and four feed entries.
    const { moveTask, result } = setUp();

    act(() => result.current.grab(task, 'col1', 'row1'));
    act(() => result.current.step('right'));
    act(() => result.current.step('down'));

    expect(result.current.isTarget('col2', 'row2')).toBe(true);
    expect(result.current.isTarget('col1', 'row1')).toBe(false);
    expect(moveTask).not.toHaveBeenCalled();
  });

  test('dropping moves the card once, to the cell the target ended on', async () => {
    const { moveTask, result } = setUp();

    act(() => result.current.grab(task, 'col1', 'row1'));
    act(() => result.current.step('right'));
    await act(async () => result.current.drop());

    expect(moveTask).toHaveBeenCalledTimes(1);
    expect(moveTask).toHaveBeenCalledWith('t1', 'col2', 'row1');
    expect(result.current.held).toBeNull();
  });

  test('dropping a card back where it started is not a move', async () => {
    const { moveTask, result } = setUp();

    act(() => result.current.grab(task, 'col1', 'row1'));
    act(() => result.current.step('right'));
    act(() => result.current.step('left'));
    await act(async () => result.current.drop());

    expect(moveTask).not.toHaveBeenCalled();
    expect(result.current.announcement.key).toBe('board.keyboardMove.unchanged');
  });

  test('cancelling puts the card back and calls nothing', async () => {
    const { moveTask, result } = setUp();

    act(() => result.current.grab(task, 'col1', 'row1'));
    act(() => result.current.step('right'));
    act(() => result.current.cancel());

    expect(moveTask).not.toHaveBeenCalled();
    expect(result.current.held).toBeNull();
    expect(result.current.announcement.key).toBe('board.keyboardMove.cancelled');
  });

  test('stepping, dropping and cancelling with nothing held do nothing at all', async () => {
    const { moveTask, result } = setUp();

    act(() => result.current.step('right'));
    await act(async () => result.current.drop());
    act(() => result.current.cancel());

    expect(moveTask).not.toHaveBeenCalled();
    expect(result.current.announcement).toBeNull();
  });

  test('a card with no cell cannot be picked up', () => {
    // A task off the board has no cell to step from, and grabbing it would leave a held card whose
    // every arrow press is a no-op with no way to tell that from a broken keyboard.
    const { result } = setUp();

    act(() => result.current.grab(task, 'col1', null));
    act(() => result.current.grab(null, 'col1', 'row1'));

    expect(result.current.held).toBeNull();
  });

  test('what is announced is a key and its values, never a sentence', () => {
    // The activity feed's rule. A sentence composed here is one the other eight locale bundles
    // cannot translate, and the live region is read aloud in the reader's language or not at all.
    const { result } = setUp();

    act(() => result.current.grab(task, 'col1', 'row1'));
    act(() => result.current.step('right'));

    expect(result.current.announcement.key).toBe('board.keyboardMove.over');
    expect(result.current.announcement.values).toEqual({ column: 'In Progress', row: 'Features' });
  });

  test('a step that hits the edge does not re-announce the cell it is already on', () => {
    // Announcing again would read as movement that did not happen.
    const { result } = setUp();

    act(() => result.current.grab(task, 'col1', 'row1'));
    act(() => result.current.step('left'));

    expect(result.current.announcement.key).toBe('board.keyboardMove.grabbed');
  });
});
