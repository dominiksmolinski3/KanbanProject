export const WIP_NEAR_RATIO = 0.8;

export function wipState(count, limit) {
  if (!limit || limit <= 0) return 'none';
  if (count > limit) return 'over';
  if (count >= limit * WIP_NEAR_RATIO) return 'near';
  return 'ok';
}

export function wipFill(count, limit) {
  if (!limit || limit <= 0) return 0;
  return Math.min(100, Math.round((count / limit) * 100));
}

const cellKey = (columnId, rowId) => `${columnId}::${rowId ?? ''}`;

function withWip(item, count) {
  const state = wipState(count, item.wipLimit);
  return {
    ...item,
    taskCount: count,
    wipState: state,
    isOverLimit: state === 'over',
    wipFill: wipFill(count, item.wipLimit),
  };
}

// WIP counts always cover every task; only the cells honour the daily-focus filter.
export function buildBoardModel({ columns = [], rows = [], tasks = [], dailyFocusOnly = false }) {
  const columnCounts = new Map();
  const rowCounts = new Map();
  const cells = new Map();
  let dailyFocusCount = 0;

  for (const task of tasks) {
    columnCounts.set(task.columnId, (columnCounts.get(task.columnId) || 0) + 1);
    rowCounts.set(task.rowId, (rowCounts.get(task.rowId) || 0) + 1);
    if (task.dailyFocus) dailyFocusCount += 1;
    if (dailyFocusOnly && !task.dailyFocus) continue;
    const key = cellKey(task.columnId, task.rowId);
    if (!cells.has(key)) cells.set(key, []);
    cells.get(key).push(task);
  }

  const enhancedColumns = columns.map((column) => withWip(column, columnCounts.get(column.id) || 0));
  const enhancedRows = rows.map((row) => withWip(row, rowCounts.get(row.id) || 0));

  return {
    columns: enhancedColumns,
    rows: enhancedRows,
    dailyFocusCount,
    tasksIn: (columnId, rowId = null) => cells.get(cellKey(columnId, rowId)) || [],
  };
}
