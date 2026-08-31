import React from 'react';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { KanbanProvider, useKanban } from '../../context/KanbanContext';
import * as api from '../../services/api';
import { toast } from 'react-toastify';

jest.mock('../../services/api', () => ({
  fetchColumns: jest.fn(),
  fetchTasks: jest.fn(),
  fetchRows: jest.fn(),
  updateTaskColumn: jest.fn(),
  updateTaskRow: jest.fn(),
  deleteTask: jest.fn(),
  addTask: jest.fn(),
  addColumn: jest.fn(),
  addRow: jest.fn(),
  updateColumnWipLimit: jest.fn(),
  updateRowWipLimit: jest.fn(),
  deleteColumn: jest.fn(),
  deleteRow: jest.fn(),
  reorderColumns: jest.fn(),
  reorderRows: jest.fn(),
  reorderTasks: jest.fn(),
  updateTaskName: jest.fn(),
  updateRowName: jest.fn(),
  updateColumnName: jest.fn(),
  getUserWipStatus: jest.fn(),
  updateUserWipLimit: jest.fn(),
  updateTaskCompletion: jest.fn(),
  setTaskDailyFocus: jest.fn(),
  ParentTaskNotCompletedError: class ParentTaskNotCompletedError extends Error {},
  ConcurrentModificationError: class ConcurrentModificationError extends Error {},
  getActiveBoardId: jest.fn(() => null),
  setActiveBoardId: jest.fn(),
}));

jest.mock('../../services/boardApi', () => ({
  fetchBoards: jest.fn(() => Promise.resolve([
    { id: 1, name: 'Kanban', ownerId: 1, owned: true, members: [] }
  ])),
  fetchCurrentBoard: jest.fn(() => Promise.resolve(
    { id: 1, name: 'Kanban', ownerId: 1, owned: true, members: [] }
  )),
  createBoard: jest.fn(),
  renameBoard: jest.fn(),
  deleteBoard: jest.fn(),
  addBoardMember: jest.fn(),
  removeBoardMember: jest.fn(),
}));

jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), success: jest.fn(), info: jest.fn(), warning: jest.fn() }
}));

const columns = [
  { id: 1, name: 'To Do', position: 0, wipLimit: 0 },
  { id: 2, name: 'Doing', position: 1, wipLimit: 0 },
  { id: 3, name: 'Done', position: 2, wipLimit: 0 }
];
const rows = [
  { id: 10, name: 'Features', position: 0, wipLimit: 0 },
  { id: 11, name: 'Bugs', position: 1, wipLimit: 0 }
];
const tasks = [
  { id: 100, title: 'First', columnId: 1, rowId: 10, position: 0 },
  { id: 101, title: 'Second', columnId: 1, rowId: 10, position: 1 },
  { id: 102, title: 'Third', columnId: 1, rowId: 10, position: 2 }
];

const Probe = () => {
  const context = useKanban();
  if (context.loading) return <div>Loading...</div>;

  return (
    <div>
      <div data-testid="columns">{context.columns.map(c => c.name).join(',')}</div>
      <div data-testid="rows">{context.rows.map(r => r.name).join(',')}</div>
      {/*
        * Each handler rethrows after reporting, so a caller can react to a failure as well as see
        * the toast. This probe is a caller that has nothing to add, and swallowing keeps a
        * deliberate rejection from being reported as an unhandled one.
        */}
      <button onClick={() => context.moveColumn(3, 1).catch(() => {})}>Move column</button>
      <button onClick={() => context.moveRow(11, 10).catch(() => {})}>Move row</button>
      <button onClick={() => context.dragAndDrop.handleTaskReorder(102, 100).catch(() => {})}>
        Reorder tasks
      </button>
    </div>
  );
};

async function renderProvider() {
  await act(async () => {
    render(<KanbanProvider><Probe /></KanbanProvider>);
  });
  await waitFor(() => expect(screen.queryByText('Loading...')).not.toBeInTheDocument());
}

/**
 * A drag is one call now, not one per item.
 *
 * The loop it replaces sent a PATCH per card and swallowed each failure separately. That was merely
 * wasteful while a lost update was silent; since the entities gained a version, one of those calls
 * can come back 409 and the ones before it stay applied - a board half in the old order and half in
 * the new, which is the one arrangement neither person asked for.
 */
describe('reordering through the context', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.fetchColumns.mockResolvedValue(columns);
    api.fetchRows.mockResolvedValue(rows);
    api.fetchTasks.mockResolvedValue(tasks);
  });

  test('moving a column sends one call carrying the whole new order', async () => {
    api.reorderColumns.mockResolvedValue([
      { id: 3, name: 'Done', position: 0, wipLimit: 0 },
      { id: 1, name: 'To Do', position: 1, wipLimit: 0 },
      { id: 2, name: 'Doing', position: 2, wipLimit: 0 }
    ]);
    await renderProvider();

    await act(async () => {
      fireEvent.click(screen.getByText('Move column'));
    });

    expect(api.reorderColumns).toHaveBeenCalledTimes(1);
    expect(api.reorderColumns).toHaveBeenCalledWith([3, 1, 2]);
    expect(screen.getByTestId('columns')).toHaveTextContent('Done,To Do,Doing');
    expect(toast.success).toHaveBeenCalledWith('notifications.columnMoved');
  });

  test('moving a swimlane sends one call too', async () => {
    api.reorderRows.mockResolvedValue([
      { id: 11, name: 'Bugs', position: 0, wipLimit: 0 },
      { id: 10, name: 'Features', position: 1, wipLimit: 0 }
    ]);
    await renderProvider();

    await act(async () => {
      fireEvent.click(screen.getByText('Move row'));
    });

    expect(api.reorderRows).toHaveBeenCalledWith([11, 10]);
    expect(screen.getByTestId('rows')).toHaveTextContent('Bugs,Features');
  });

  test('reordering a cell sends the ids in their new order, once', async () => {
    api.reorderTasks.mockResolvedValue([]);
    await renderProvider();

    await act(async () => {
      fireEvent.click(screen.getByText('Reorder tasks'));
    });

    expect(api.reorderTasks).toHaveBeenCalledTimes(1);
    expect(api.reorderTasks).toHaveBeenCalledWith([102, 100, 101]);
  });

  test('a conflict says so and reloads, rather than reporting a failure', async () => {
    api.reorderTasks.mockRejectedValue(new api.ConcurrentModificationError());
    await renderProvider();
    api.fetchTasks.mockClear();

    await act(async () => {
      fireEvent.click(screen.getByText('Reorder tasks'));
    });

    // Nothing is broken: the whole batch rolled back, so the honest response is to say what
    // happened and show the order the board actually has.
    expect(toast.info).toHaveBeenCalledWith('notifications.changedBySomeoneElse');
    expect(toast.error).not.toHaveBeenCalled();
    expect(api.fetchTasks).toHaveBeenCalled();
  });

  test('a column conflict reloads the whole board, since positions moved under it', async () => {
    api.reorderColumns.mockRejectedValue(new api.ConcurrentModificationError());
    await renderProvider();
    api.fetchColumns.mockClear();

    await act(async () => {
      fireEvent.click(screen.getByText('Move column'));
    });

    expect(toast.info).toHaveBeenCalledWith('notifications.changedBySomeoneElse');
    expect(api.fetchColumns).toHaveBeenCalled();
    expect(toast.success).not.toHaveBeenCalled();
  });

  test('a failure that is not a conflict is still reported as one', async () => {
    api.reorderRows.mockRejectedValue(new Error('Error reordering swimlanes: 500'));
    await renderProvider();

    await act(async () => {
      fireEvent.click(screen.getByText('Move row'));
    });

    expect(toast.error).toHaveBeenCalled();
    expect(toast.info).not.toHaveBeenCalled();
  });
});
