import React from 'react';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { KanbanProvider, useKanban } from '../../context/KanbanContext';
import * as api from '../../services/api';
import { ApiError } from '../../services/apiError';
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
  inviteToBoard: jest.fn(),
  fetchBoardInvitations: jest.fn(),
  revokeBoardInvitation: jest.fn(),
  fetchMyInvitations: jest.fn(),
  acceptInvitation: jest.fn(),
  declineInvitation: jest.fn(),
  removeBoardMember: jest.fn(),
}));

jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), success: jest.fn(), info: jest.fn(), warning: jest.fn() }
}));

const columns = [
  { id: 1, name: 'To Do', position: 0, wipLimit: 0 },
  { id: 2, name: 'Doing', position: 1, wipLimit: 0 }
];
const rows = [
  { id: 10, name: 'Features', position: 0, wipLimit: 0 },
  { id: 11, name: 'Bugs', position: 1, wipLimit: 0 }
];
const tasks = [
  { id: 100, title: 'First', columnId: 1, rowId: 10, position: 0 },
  { id: 101, title: 'Second', columnId: 1, rowId: 10, position: 1 }
];

const refused = () => new ApiError('Check the fields and try again.', { status: 400, code: 'VALIDATION_ERROR' });

const results = {};

const Probe = () => {
  const context = useKanban();
  if (context.loading) return <div>Loading...</div>;
  if (context.error) return <div role="alert">Board error: {context.error}</div>;

  const record = (name, promise) => {
    results[name] = promise.then(value => ({ value }), error => ({ error }));
  };

  return (
    <div>
      <div data-testid="columns">{context.columns.map(c => c.name).join(',')}</div>
      <div data-testid="rows">{context.rows.map(r => r.name).join(',')}</div>
      <div data-testid="tasks">{context.tasks.map(task => task.title).join(',')}</div>
      <button onClick={() => record('column', context.updateColumnName(1, 'x'.repeat(300)))}>Rename column</button>
      <button onClick={() => record('row', context.updateRowName(10, 'x'.repeat(300)))}>Rename row</button>
      <button onClick={() => record('task', context.updateTaskName(100, 'x'.repeat(300)))}>Rename task</button>
      <button onClick={() => record('moveColumn', context.moveColumn(2, 1))}>Move column</button>
      <button onClick={() => record('moveRow', context.moveRow(11, 10))}>Move row</button>
      <button onClick={() => record('reorder', context.dragAndDrop.handleTaskReorder(101, 100))}>Reorder</button>
      <button onClick={() => record('moveTask', context.moveTask(100, 2, 10))}>Move task</button>
    </div>
  );
};

async function renderProvider() {
  await act(async () => {
    render(<KanbanProvider><Probe /></KanbanProvider>);
  });
  await waitFor(() => expect(screen.queryByText('Loading...')).not.toBeInTheDocument());
}

async function click(label) {
  await act(async () => {
    fireEvent.click(screen.getByText(label));
  });
}

describe('a refused edit keeps the board on screen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(console, 'error').mockImplementation(() => {});
    api.fetchColumns.mockResolvedValue(columns);
    api.fetchRows.mockResolvedValue(rows);
    api.fetchTasks.mockResolvedValue(tasks);
  });

  afterEach(() => {
    console.error.mockRestore();
  });

  test.each([
    ['Rename column', 'column', () => api.updateColumnName],
    ['Rename row', 'row', () => api.updateRowName],
    ['Rename task', 'task', () => api.updateTaskName],
  ])('%s refused with a 400 is a toast and the old name stays', async (label, key, endpoint) => {
    endpoint().mockRejectedValueOnce(refused());
    await renderProvider();

    await click(label);

    expect(await results[key]).toEqual({ value: false });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByTestId('columns')).toHaveTextContent('To Do,Doing');
    expect(screen.getByTestId('rows')).toHaveTextContent('Features,Bugs');
    expect(screen.getByTestId('tasks')).toHaveTextContent('First,Second');
    expect(toast.error).toHaveBeenCalledWith('notifications.errorOccurred');
  });

  test.each([
    ['Move column', 'moveColumn', () => api.reorderColumns, () => api.fetchColumns],
    ['Move row', 'moveRow', () => api.reorderRows, () => api.fetchRows],
    ['Reorder', 'reorder', () => api.reorderTasks, () => api.fetchTasks],
    ['Move task', 'moveTask', () => api.updateTaskColumn, () => api.fetchTasks],
  ])('%s that fails resyncs from the server and settles', async (label, key, endpoint, reread) => {
    endpoint().mockRejectedValueOnce(new ApiError('Something went wrong.', { status: 500 }));
    await renderProvider();
    const readsBefore = reread().mock.calls.length;

    await click(label);

    expect(await results[key]).not.toHaveProperty('error');
    expect(reread().mock.calls.length).toBeGreaterThan(readsBefore);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByTestId('columns')).toHaveTextContent('To Do,Doing');
    expect(screen.getByTestId('rows')).toHaveTextContent('Features,Bugs');
    expect(toast.error).toHaveBeenCalledTimes(1);
  });

  test('a failed board read still replaces the board', async () => {
    api.fetchColumns.mockRejectedValueOnce(new Error('unreachable'));
    await act(async () => {
      render(<KanbanProvider><Probe /></KanbanProvider>);
    });

    expect(await screen.findByRole('alert')).toHaveTextContent('Board error: unreachable');
  });
});
