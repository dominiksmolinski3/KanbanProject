import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import KanbanContext from '../../context/KanbanContext';
import Task from '../../components/Task';
import Board from '../../components/Board';

jest.mock('../../services/api', () => ({
  getUserAvatar: jest.fn().mockResolvedValue(null),
  assignUserToTask: jest.fn().mockResolvedValue({}),
  fetchSubTasksByTaskId: jest.fn().mockResolvedValue([]),
  fetchTask: jest.fn().mockResolvedValue({}),
  getChildTasks: jest.fn().mockResolvedValue([]),
  WipLimitExceededError: class WipLimitExceededError extends Error {}
}));

jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), success: jest.fn(), info: jest.fn(), warning: jest.fn() }
}));

jest.mock('../../components/EditableText', () => {
  return function MockEditableText({ text }) {
    return <div className="editable-text">{text}</div>;
  };
});

const openTask = {
  id: 1,
  title: 'Write the migration',
  userIds: [],
  labels: [],
  columnId: 'col1',
  rowId: 'row1',
  completed: false,
  dailyFocus: false
};

function contextValue(overrides = {}) {
  return {
    deleteTask: jest.fn(),
    refreshTasks: jest.fn(),
    updateTaskName: jest.fn(),
    updateTaskCompletion: jest.fn(),
    setDailyFocus: jest.fn(),
    dragAndDrop: {
      handleTaskReorder: jest.fn(),
      handleDragStart: jest.fn(),
      handleDragOver: jest.fn(),
      handleDrop: jest.fn(),
      handleDragEnd: jest.fn()
    },
    ...overrides
  };
}

async function renderTask(task, value) {
  await act(async () => {
    render(
      <KanbanContext.Provider value={value}>
        <Task task={task} columnId="col1" />
      </KanbanContext.Provider>
    );
  });
}

describe('the completion checkbox', () => {
  test('reflects the task the server sent', async () => {
    await renderTask({ ...openTask, completed: true }, contextValue());

    expect(screen.getByRole('checkbox')).toBeChecked();
  });

  test('asks to complete an open task', async () => {
    const value = contextValue();
    await renderTask(openTask, value);

    await act(async () => {
      fireEvent.click(screen.getByRole('checkbox'));
    });

    expect(value.updateTaskCompletion).toHaveBeenCalledWith(1, true);
  });

  test('asks to reopen a completed one', async () => {
    const value = contextValue();
    await renderTask({ ...openTask, completed: true }, value);

    await act(async () => {
      fireEvent.click(screen.getByRole('checkbox'));
    });

    expect(value.updateTaskCompletion).toHaveBeenCalledWith(1, false);
  });

  test('does not open the details panel behind it', async () => {
    // The card opens the panel on any click it does not recognise, and the two new controls
    // sit inside the card.
    await renderTask(openTask, contextValue());

    await act(async () => {
      fireEvent.click(screen.getByRole('checkbox'));
    });

    expect(screen.queryByText('taskActions.subtasks')).not.toBeInTheDocument();
  });
});

describe('the daily focus star', () => {
  test('sets the flag on a task that does not carry it', async () => {
    const value = contextValue();
    await renderTask(openTask, value);

    await act(async () => {
      fireEvent.click(screen.getByTitle('taskActions.addToDailyFocus'));
    });

    expect(value.setDailyFocus).toHaveBeenCalledWith(1, true);
  });

  test('clears it on a task that does', async () => {
    const value = contextValue();
    await renderTask({ ...openTask, dailyFocus: true }, value);

    await act(async () => {
      fireEvent.click(screen.getByTitle('taskActions.removeFromDailyFocus'));
    });

    expect(value.setDailyFocus).toHaveBeenCalledWith(1, false);
  });

  test('reports its state to assistive technology', async () => {
    await renderTask({ ...openTask, dailyFocus: true }, contextValue());

    expect(screen.getByTitle('taskActions.removeFromDailyFocus')).toHaveAttribute('aria-pressed', 'true');
  });
});

describe('the board daily-focus filter', () => {
  const columns = [{ id: 'col1', name: 'To Do', position: 0, wipLimit: 0 }];
  const rows = [{ id: 'row1', name: 'Features', position: 0, wipLimit: 0 }];
  const tasks = [
    { id: 1, title: 'Focused', columnId: 'col1', rowId: 'row1', dailyFocus: true },
    { id: 2, title: 'Everything else', columnId: 'col1', rowId: 'row1', dailyFocus: false }
  ];

  function boardValue(overrides = {}) {
    return {
      columns,
      rows,
      tasks,
      loading: false,
      error: null,
      deleteRow: jest.fn(),
      deleteColumn: jest.fn(),
      updateColumnName: jest.fn(),
      updateRowName: jest.fn(),
      dailyFocusOnly: false,
      setDailyFocusOnly: jest.fn(),
      dragAndDrop: {
        handleDragStart: jest.fn(),
        handleDragOver: jest.fn(),
        handleDrop: jest.fn(),
        handleDragEnd: jest.fn(),
        handleTaskReorder: jest.fn()
      },
      ...overrides
    };
  }

  async function renderBoard(value) {
    await act(async () => {
      render(
        <KanbanContext.Provider value={value}>
          <Board />
        </KanbanContext.Provider>
      );
    });
  }

  test('shows every task while the filter is off', async () => {
    await renderBoard(boardValue());

    expect(screen.getByText('Focused')).toBeInTheDocument();
    expect(screen.getByText('Everything else')).toBeInTheDocument();
  });

  test('shows only the focused ones while it is on', async () => {
    await renderBoard(boardValue({ dailyFocusOnly: true }));

    expect(screen.getByText('Focused')).toBeInTheDocument();
    expect(screen.queryByText('Everything else')).not.toBeInTheDocument();
  });

  test('counts the focused tasks whether or not it is filtering', async () => {
    await renderBoard(boardValue());

    expect(screen.getByText('1')).toBeInTheDocument();
  });

  test('toggling asks the context to flip it', async () => {
    const value = boardValue();
    await renderBoard(value);

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: /board\.dailyFocus/ }));
    });

    expect(value.setDailyFocusOnly).toHaveBeenCalledWith(true);
  });

  test('says so when the filter is on and nothing is focused', async () => {
    await renderBoard(boardValue({
      dailyFocusOnly: true,
      tasks: [{ id: 2, title: 'Everything else', columnId: 'col1', rowId: 'row1', dailyFocus: false }]
    }));

    expect(screen.getByText('board.dailyFocusEmpty')).toBeInTheDocument();
  });
});
