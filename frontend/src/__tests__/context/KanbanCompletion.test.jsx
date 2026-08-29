import React from 'react';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { KanbanProvider, useKanban } from '../../context/KanbanContext';
import * as api from '../../services/api';
import { toast } from 'react-toastify';

class ParentTaskNotCompletedError extends Error {
  constructor(taskId) {
    super(`Task ${taskId} has a parent that is still open`);
    this.name = 'ParentTaskNotCompletedError';
    this.taskId = taskId;
  }
}

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
  updateColumnPosition: jest.fn(),
  updateRowPosition: jest.fn(),
  updateTaskPosition: jest.fn(),
  updateTaskName: jest.fn(),
  updateRowName: jest.fn(),
  updateColumnName: jest.fn(),
  getUserWipStatus: jest.fn(),
  updateUserWipLimit: jest.fn(),
  updateTaskCompletion: jest.fn(),
  setTaskDailyFocus: jest.fn(),
  ParentTaskNotCompletedError: class ParentTaskNotCompletedError extends Error {}
}));

jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), success: jest.fn(), info: jest.fn(), warning: jest.fn() }
}));

const columns = [{ id: 'col1', name: 'To Do', position: 0, wipLimit: 0 }];
const rows = [{ id: 'row1', name: 'Features', position: 0, wipLimit: 0 }];
const tasks = [
  { id: 1, title: 'Parent', columnId: 'col1', rowId: 'row1', position: 0, completed: false, dailyFocus: false },
  { id: 2, title: 'Child', columnId: 'col1', rowId: 'row1', position: 1, completed: true, dailyFocus: false }
];

const Probe = () => {
  const context = useKanban();
  if (context.loading) return <div>Loading...</div>;

  return (
    <div>
      <div data-testid="completed">{context.tasks.filter(t => t.completed).map(t => t.title).join(',')}</div>
      <div data-testid="focused">{context.tasks.filter(t => t.dailyFocus).map(t => t.title).join(',')}</div>
      <button onClick={() => context.updateTaskCompletion(1, true)}>Complete</button>
      <button onClick={() => context.updateTaskCompletion(2, false)}>Reopen</button>
      <button onClick={() => context.setDailyFocus(1, true)}>Focus</button>
    </div>
  );
};

async function renderProvider() {
  await act(async () => {
    render(<KanbanProvider><Probe /></KanbanProvider>);
  });
  await waitFor(() => expect(screen.queryByText('Loading...')).not.toBeInTheDocument());
}

describe('completion through the context', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.fetchColumns.mockResolvedValue(columns);
    api.fetchRows.mockResolvedValue(rows);
    api.fetchTasks.mockResolvedValue(tasks);
  });

  test('a completed task is marked completed locally and announced', async () => {
    api.updateTaskCompletion.mockResolvedValue({ id: 1, completed: true });
    await renderProvider();

    await act(async () => {
      fireEvent.click(screen.getByText('Complete'));
    });

    expect(api.updateTaskCompletion).toHaveBeenCalledWith(1, true);
    expect(screen.getByTestId('completed')).toHaveTextContent('Parent');
    expect(toast.success).toHaveBeenCalledWith('notifications.taskCompleted');
  });

  test('un-completing refetches, because the server also changed the dependents', async () => {
    api.updateTaskCompletion.mockResolvedValue({ id: 2, completed: false });
    await renderProvider();
    api.fetchTasks.mockClear();

    await act(async () => {
      fireEvent.click(screen.getByText('Reopen'));
    });

    // The response describes the task that was asked about; the cascade below it is only
    // visible in a fresh listing.
    expect(api.fetchTasks).toHaveBeenCalled();
  });

  test('completing does not refetch, because nothing else moved', async () => {
    api.updateTaskCompletion.mockResolvedValue({ id: 1, completed: true });
    await renderProvider();
    api.fetchTasks.mockClear();

    await act(async () => {
      fireEvent.click(screen.getByText('Complete'));
    });

    expect(api.fetchTasks).not.toHaveBeenCalled();
  });

  test('an open parent is explained, not reported as an unknown failure', async () => {
    api.updateTaskCompletion.mockRejectedValue(new api.ParentTaskNotCompletedError());
    await renderProvider();

    await act(async () => {
      fireEvent.click(screen.getByText('Complete'));
    });

    expect(toast.error).toHaveBeenCalledWith('notifications.parentTaskNotCompleted');
    expect(screen.getByTestId('completed')).not.toHaveTextContent('Parent');
  });

  test('any other failure keeps the generic message', async () => {
    api.updateTaskCompletion.mockRejectedValue(new Error('boom'));
    await renderProvider();

    await act(async () => {
      fireEvent.click(screen.getByText('Complete'));
    });

    expect(toast.error).toHaveBeenCalledWith('notifications.errorOccurred');
  });
});

describe('daily focus through the context', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.fetchColumns.mockResolvedValue(columns);
    api.fetchRows.mockResolvedValue(rows);
    api.fetchTasks.mockResolvedValue(tasks);
  });

  test('setting the flag updates the task the board renders', async () => {
    api.setTaskDailyFocus.mockResolvedValue({ id: 1, dailyFocus: true });
    await renderProvider();

    await act(async () => {
      fireEvent.click(screen.getByText('Focus'));
    });

    expect(api.setTaskDailyFocus).toHaveBeenCalledWith(1, true);
    expect(screen.getByTestId('focused')).toHaveTextContent('Parent');
    expect(toast.success).toHaveBeenCalledWith('notifications.dailyFocusAdded');
  });

  test('a failure leaves the flag alone rather than showing it set', async () => {
    api.setTaskDailyFocus.mockRejectedValue(new Error('boom'));
    await renderProvider();

    await act(async () => {
      fireEvent.click(screen.getByText('Focus'));
    });

    expect(screen.getByTestId('focused')).toBeEmptyDOMElement();
  });
});
