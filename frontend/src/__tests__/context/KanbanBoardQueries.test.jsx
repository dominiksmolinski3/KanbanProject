import React from 'react';
import { render, act, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { useKanban } from '../../context/KanbanContext';
import { KanbanProvider } from '../../test-utils/KanbanTestProvider';
import * as api from '../../services/api';

jest.mock('../../services/boardEvents', () => jest.fn().mockImplementation(function BoardEvents() {
  this.watch = jest.fn();
  this.stop = jest.fn();
}));

jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn(), info: jest.fn() },
}));

jest.mock('../../services/api', () => ({
  fetchColumns: jest.fn(),
  fetchTasks: jest.fn(),
  fetchRows: jest.fn(),
  getActiveBoardId: jest.fn(() => null),
  setActiveBoardId: jest.fn(),
}));

jest.mock('../../services/boardApi', () => ({
  fetchBoards: jest.fn(),
  fetchCurrentBoard: jest.fn(),
  fetchMyInvitations: jest.fn(() => Promise.resolve([])),
}));

const boardApi = require('../../services/boardApi');

const board = (id) => ({ id, name: `Board ${id}`, ownerId: 1, owned: true, members: [] });
const column = (id, position = 0) => ({ id, name: `Column ${id}`, position });
const task = (id, overrides = {}) => ({ id, title: `Task ${id}`, columnId: 10, rowId: 20, ...overrides });

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

describe('the board read through queries', () => {
  let kanban;

  const Probe = () => {
    kanban = useKanban();
    return null;
  };

  const mount = async () => {
    await act(async () => {
      render(<KanbanProvider><Probe /></KanbanProvider>);
    });
  };

  beforeEach(() => {
    jest.clearAllMocks();
    boardApi.fetchBoards.mockResolvedValue([board(1), board(2)]);
    boardApi.fetchCurrentBoard.mockResolvedValue(board(1));
    api.fetchColumns.mockResolvedValue([column(10)]);
    api.fetchRows.mockResolvedValue([{ id: 20, name: 'Lane', position: 0 }]);
    api.fetchTasks.mockResolvedValue([task(1)]);
    api.getActiveBoardId.mockReturnValue(null);
  });

  it('reads columns, rows and tasks in order of position', async () => {
    api.fetchColumns.mockResolvedValue([column(11, 2), column(10, 1)]);
    api.fetchRows.mockResolvedValue([{ id: 21, name: 'B', position: 5 }, { id: 20, name: 'A', position: 1 }]);

    await mount();
    await waitFor(() => expect(kanban.loading).toBe(false));

    expect(kanban.columns.map((c) => c.id)).toEqual([10, 11]);
    expect(kanban.rows.map((r) => r.id)).toEqual([20, 21]);
  });

  it('puts a task that has no row into the first one, also after a refresh', async () => {
    api.fetchTasks.mockResolvedValue([task(1, { rowId: null })]);

    await mount();
    await waitFor(() => expect(kanban.tasks.map((t) => t.rowId)).toEqual([20]));

    api.fetchTasks.mockResolvedValue([task(1, { rowId: null }), task(2, { rowId: null })]);
    await act(async () => {
      await kanban.refreshTasks();
    });

    expect(kanban.tasks.map((t) => t.rowId)).toEqual([20, 20]);
  });

  it('leaves a task without a row alone while the board has no rows', async () => {
    api.fetchRows.mockResolvedValue([]);
    api.fetchTasks.mockResolvedValue([task(1, { rowId: null })]);

    await mount();
    await waitFor(() => expect(kanban.tasks).toHaveLength(1));

    expect(kanban.tasks[0].rowId).toBeNull();
  });

  it('still shows the board when only the rows could not be read', async () => {
    api.fetchRows.mockRejectedValue(new Error('rows are down'));
    console.error = jest.fn();

    await mount();
    await waitFor(() => expect(kanban.loading).toBe(false));

    expect(kanban.error).toBeNull();
    expect(kanban.columns).toHaveLength(1);
    expect(kanban.rows).toEqual([]);
    expect(kanban.tasks).toHaveLength(1);
  });

  it('reports a board whose tasks could not be read', async () => {
    api.fetchTasks.mockRejectedValue(new Error('tasks are down'));
    console.error = jest.fn();

    await mount();

    await waitFor(() => expect(kanban.error).toBe('tasks are down'));
    expect(kanban.loading).toBe(false);
  });

  it('reads all three again for a board refresh, and only the tasks for a task refresh', async () => {
    await mount();
    await waitFor(() => expect(kanban.loading).toBe(false));
    jest.clearAllMocks();

    await act(async () => {
      await kanban.refreshTasks();
    });
    expect(api.fetchTasks).toHaveBeenCalledTimes(1);
    expect(api.fetchColumns).not.toHaveBeenCalled();
    expect(api.fetchRows).not.toHaveBeenCalled();

    await act(async () => {
      await kanban.refreshBoard();
    });
    expect(api.fetchColumns).toHaveBeenCalledTimes(1);
    expect(api.fetchRows).toHaveBeenCalledTimes(1);
    expect(api.fetchTasks).toHaveBeenCalledTimes(2);
  });

  it('shows nothing of the old board while the next one loads', async () => {
    await mount();
    await waitFor(() => expect(kanban.tasks.map((t) => t.id)).toEqual([1]));

    const next = deferred();
    api.fetchTasks.mockReturnValueOnce(next.promise);
    api.fetchColumns.mockResolvedValueOnce([column(30)]);
    await act(async () => {
      kanban.selectBoard(2);
    });

    expect(kanban.activeBoardId).toBe(2);
    expect(kanban.tasks).toEqual([]);

    await act(async () => {
      next.resolve([task(9, { columnId: 30 })]);
      await next.promise;
    });
    await waitFor(() => expect(kanban.tasks.map((t) => t.id)).toEqual([9]));
    expect(kanban.columns.map((c) => c.id)).toEqual([30]);
  });

  it('keeps each board\'s data apart, so going back shows it at once', async () => {
    await mount();
    await waitFor(() => expect(kanban.tasks.map((t) => t.id)).toEqual([1]));

    api.fetchTasks.mockResolvedValueOnce([task(9)]);
    await act(async () => {
      kanban.selectBoard(2);
    });
    await waitFor(() => expect(kanban.tasks.map((t) => t.id)).toEqual([9]));

    api.fetchTasks.mockReturnValueOnce(new Promise(() => {}));
    await act(async () => {
      kanban.selectBoard(1);
    });

    expect(kanban.tasks.map((t) => t.id)).toEqual([1]);
  });
});
