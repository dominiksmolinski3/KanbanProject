import { buildBoardModel, wipFill, wipState } from '../../board/boardModel';

describe('wipState', () => {
  test.each([
    [3, 0, 'none'],
    [3, null, 'none'],
    [0, 5, 'ok'],
    [3, 5, 'ok'],
    [4, 5, 'near'],
    [5, 5, 'near'],
    [6, 5, 'over'],
    [1, 1, 'near'],
    [2, 1, 'over'],
  ])('%i cards against a limit of %p is %s', (count, limit, expected) => {
    expect(wipState(count, limit)).toBe(expected);
  });

  test('the fill is capped at a full bar', () => {
    expect(wipFill(1, 4)).toBe(25);
    expect(wipFill(9, 4)).toBe(100);
    expect(wipFill(3, 0)).toBe(0);
  });
});

describe('buildBoardModel', () => {
  const columns = [{ id: 1, wipLimit: 2 }, { id: 2, wipLimit: 0 }];
  const rows = [{ id: 10, wipLimit: 1 }, { id: 11, wipLimit: 0 }];
  const tasks = [
    { id: 'a', columnId: 1, rowId: 10, dailyFocus: true },
    { id: 'b', columnId: 1, rowId: 10 },
    { id: 'c', columnId: 1, rowId: 11 },
    { id: 'd', columnId: 2, rowId: null },
  ];

  test('counts every card and groups each into its cell', () => {
    const model = buildBoardModel({ columns, rows, tasks });

    expect(model.columns.map((c) => [c.taskCount, c.wipState])).toEqual([[3, 'over'], [1, 'none']]);
    expect(model.rows.map((r) => [r.taskCount, r.isOverLimit])).toEqual([[2, true], [1, false]]);
    expect(model.tasksIn(1, 10).map((t) => t.id)).toEqual(['a', 'b']);
    expect(model.tasksIn(2).map((t) => t.id)).toEqual(['d']);
    expect(model.tasksIn(2, 10)).toEqual([]);
  });

  test('the daily-focus filter narrows the cells but never the WIP counts', () => {
    const model = buildBoardModel({ columns, rows, tasks, dailyFocusOnly: true });

    expect(model.tasksIn(1, 10).map((t) => t.id)).toEqual(['a']);
    expect(model.tasksIn(1, 11)).toEqual([]);
    expect(model.columns[0].taskCount).toBe(3);
    expect(model.dailyFocusCount).toBe(1);
  });
});
