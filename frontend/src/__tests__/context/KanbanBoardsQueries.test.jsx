import React from 'react';
import { render, act, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { useKanban } from '../../context/KanbanContext';
import { KanbanProvider } from '../../test-utils/KanbanTestProvider';
import * as api from '../../services/api';
import * as boardApi from '../../services/boardApi';

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
  getActiveBoardId: jest.fn(),
  setActiveBoardId: jest.fn(),
}));

jest.mock('../../services/boardApi', () => ({
  fetchBoards: jest.fn(),
  fetchCurrentBoard: jest.fn(),
  fetchMyInvitations: jest.fn(),
  deleteBoard: jest.fn(),
}));

const board = (id, overrides = {}) => ({ id, name: `Board ${id}`, ownerId: 1, owned: true, members: [], ...overrides });

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

describe('the list of boards read through queries', () => {
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
    console.error = jest.fn();
    boardApi.fetchBoards.mockResolvedValue([board(1), board(2)]);
    boardApi.fetchCurrentBoard.mockResolvedValue(board(1));
    boardApi.fetchMyInvitations.mockResolvedValue([]);
    api.getActiveBoardId.mockReturnValue(null);
    api.fetchColumns.mockResolvedValue([]);
    api.fetchRows.mockResolvedValue([]);
    api.fetchTasks.mockResolvedValue([]);
  });

  it('is loading until the board has been chosen and read, then is not', async () => {
    const boards = deferred();
    boardApi.fetchBoards.mockReturnValue(boards.promise);

    await mount();
    expect(kanban.loading).toBe(true);
    expect(kanban.activeBoardId).toBeNull();

    await act(async () => {
      boards.resolve([board(1)]);
      await boards.promise;
    });
    await waitFor(() => expect(kanban.loading).toBe(false));
    expect(kanban.activeBoardId).toBe(1);
  });

  it('opens the board the reader had open last when it is still theirs', async () => {
    api.getActiveBoardId.mockReturnValue(2);

    await mount();

    await waitFor(() => expect(kanban.activeBoardId).toBe(2));
    expect(api.setActiveBoardId).toHaveBeenCalledWith(2);
  });

  it('falls back to the current board when the remembered one is gone', async () => {
    api.getActiveBoardId.mockReturnValue(99);

    await mount();

    await waitFor(() => expect(kanban.activeBoardId).toBe(1));
    expect(api.setActiveBoardId).toHaveBeenCalledWith(1);
  });

  it('does not move the reader to another board when the list is read again', async () => {
    await mount();
    await waitFor(() => expect(kanban.activeBoardId).toBe(1));
    kanban.selectBoard(2);
    await waitFor(() => expect(kanban.activeBoardId).toBe(2));

    boardApi.fetchCurrentBoard.mockResolvedValue(board(1));
    await act(async () => {
      await kanban.refreshBoards();
    });

    expect(kanban.activeBoardId).toBe(2);
  });

  it('reports a list that could not be read, and stops loading', async () => {
    boardApi.fetchBoards.mockRejectedValue(new Error('boards are down'));

    await mount();

    await waitFor(() => expect(kanban.error).toBe('boards are down'));
    expect(kanban.loading).toBe(false);
    expect(kanban.boards).toEqual([]);
  });

  it('reports a current board that could not be read', async () => {
    boardApi.fetchCurrentBoard.mockRejectedValue(new Error('no current board'));

    await mount();

    await waitFor(() => expect(kanban.error).toBe('no current board'));
    expect(kanban.loading).toBe(false);
  });

  it('drops the error once a later read succeeds', async () => {
    api.fetchTasks.mockRejectedValueOnce(new Error('tasks are down'));

    await mount();
    await waitFor(() => expect(kanban.error).toBe('tasks are down'));

    api.fetchTasks.mockResolvedValue([]);
    await act(async () => {
      await kanban.refreshTasks();
    });

    await waitFor(() => expect(kanban.error).toBeNull());
  });

  it('keeps the invitations apart: one that cannot be read leaves the board alone', async () => {
    boardApi.fetchMyInvitations.mockRejectedValue(new Error('invitations are down'));

    await mount();
    await waitFor(() => expect(kanban.loading).toBe(false));

    expect(kanban.error).toBeNull();
    expect(kanban.myInvitations).toEqual([]);
    expect(kanban.activeBoardId).toBe(1);
  });

  it('hands back the invitations it just read, or none when it cannot', async () => {
    await mount();
    await waitFor(() => expect(kanban.loading).toBe(false));

    boardApi.fetchMyInvitations.mockResolvedValue([{ id: 5, boardName: 'Shared' }]);
    let read;
    await act(async () => {
      read = await kanban.refreshMyInvitations();
    });
    expect(read).toEqual([{ id: 5, boardName: 'Shared' }]);
    expect(kanban.myInvitations).toEqual([{ id: 5, boardName: 'Shared' }]);

    boardApi.fetchMyInvitations.mockRejectedValue(new Error('down'));
    await act(async () => {
      read = await kanban.refreshMyInvitations();
    });
    expect(read).toEqual([]);
  });

  it('hands back the boards it just read, and the list shows them', async () => {
    await mount();
    await waitFor(() => expect(kanban.boards).toHaveLength(2));

    boardApi.fetchBoards.mockResolvedValue([board(1), board(2), board(3)]);
    let read;
    await act(async () => {
      read = await kanban.refreshBoards();
    });

    expect(read.map((b) => b.id)).toEqual([1, 2, 3]);
    expect(kanban.boards.map((b) => b.id)).toEqual([1, 2, 3]);
  });

  it('deleting the open board moves to another one', async () => {
    await mount();
    await waitFor(() => expect(kanban.activeBoardId).toBe(1));

    boardApi.deleteBoard.mockResolvedValue(undefined);
    boardApi.fetchBoards.mockResolvedValue([board(2)]);
    await act(async () => {
      await kanban.deleteBoard(1);
    });

    expect(kanban.activeBoardId).toBe(2);
    expect(boardApi.fetchCurrentBoard).toHaveBeenCalledTimes(1);
  });

  it('deleting the last board asks the server for the one it makes instead', async () => {
    await mount();
    await waitFor(() => expect(kanban.activeBoardId).toBe(1));

    boardApi.deleteBoard.mockResolvedValue(undefined);
    boardApi.fetchBoards.mockResolvedValueOnce([]).mockResolvedValue([board(7)]);
    boardApi.fetchCurrentBoard.mockResolvedValue(board(7));
    await act(async () => {
      await kanban.deleteBoard(1);
    });

    expect(kanban.activeBoardId).toBe(7);
    expect(kanban.boards.map((b) => b.id)).toEqual([7]);
  });
});
