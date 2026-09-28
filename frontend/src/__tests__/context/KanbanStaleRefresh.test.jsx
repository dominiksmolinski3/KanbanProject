import React from 'react';
import { render, act, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { KanbanProvider, useKanban } from '../../context/KanbanContext';
import * as api from '../../services/api';

const watchers = [];
jest.mock('../../services/boardEvents', () => jest.fn().mockImplementation(function BoardEvents() {
  this.watch = jest.fn((boardId, onEvent) => {
    this.onEvent = onEvent;
  });
  this.stop = jest.fn();
  watchers.push(this);
}));

jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn(), info: jest.fn() },
}));

jest.mock('../../services/api', () => ({
  fetchColumns: jest.fn(() => Promise.resolve([{ id: 'c1', name: 'To Do', position: 0 }])),
  fetchTasks: jest.fn(),
  fetchRows: jest.fn(() => Promise.resolve([])),
  updateTaskColumn: jest.fn(), updateTaskRow: jest.fn(), deleteTask: jest.fn(() => Promise.resolve()),
  addTask: jest.fn(), addColumn: jest.fn(), addRow: jest.fn(), updateColumnWipLimit: jest.fn(),
  updateRowWipLimit: jest.fn(), deleteColumn: jest.fn(), deleteRow: jest.fn(),
  reorderColumns: jest.fn(), reorderRows: jest.fn(), reorderTasks: jest.fn(),
  ConcurrentModificationError: class extends Error {},
  updateTaskName: jest.fn(() => Promise.resolve()), updateRowName: jest.fn(), updateColumnName: jest.fn(),
  getUserWipStatus: jest.fn(), updateUserWipLimit: jest.fn(), updateTaskCompletion: jest.fn(),
  setTaskDailyFocus: jest.fn(), ParentTaskNotCompletedError: class extends Error {},
  getActiveBoardId: jest.fn(() => null), setActiveBoardId: jest.fn(),
}));

jest.mock('../../services/boardApi', () => ({
  fetchBoards: jest.fn(() => Promise.resolve([{ id: 1, name: 'Kanban', ownerId: 1, owned: true, members: [] }])),
  fetchCurrentBoard: jest.fn(() => Promise.resolve({ id: 1, name: 'Kanban', ownerId: 1, owned: true, members: [] })),
  createBoard: jest.fn(), renameBoard: jest.fn(), deleteBoard: jest.fn(), inviteToBoard: jest.fn(),
  revokeBoardInvitation: jest.fn(), fetchMyInvitations: jest.fn(() => Promise.resolve([])),
  acceptInvitation: jest.fn(), declineInvitation: jest.fn(), removeBoardMember: jest.fn(),
}));

const SIX = { id: 6, title: 'Six', columnId: 'c1', rowId: null };
const SEVEN = { id: 7, title: 'Seven', columnId: 'c1', rowId: null };

function deferred() {
  let resolve;
  const promise = new Promise((done) => { resolve = done; });
  return { promise, resolve };
}

describe('a live refresh that answers after a local change', () => {
  let board;

  const Probe = () => {
    board = useKanban();
    return null;
  };

  beforeEach(async () => {
    jest.useFakeTimers();
    watchers.length = 0;
    jest.clearAllMocks();
    api.fetchTasks.mockResolvedValue([SIX, SEVEN]);

    await act(async () => {
      render(<KanbanProvider><Probe /></KanbanProvider>);
    });
    await waitFor(() => expect(board.tasks.map((task) => task.id)).toEqual([6, 7]));
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  const startLiveRefresh = async () => {
    const stale = deferred();
    api.fetchTasks.mockReturnValueOnce(stale.promise);
    await act(async () => {
      watchers[0].onEvent({ type: 'TASKS', boardId: 1 });
      jest.advanceTimersByTime(300);
    });
    expect(api.fetchTasks).toHaveBeenCalledTimes(2);
    return stale;
  };

  it('does not bring back a task deleted while it was in flight', async () => {
    const stale = await startLiveRefresh();

    await act(async () => {
      await board.deleteTask(6);
    });
    expect(board.tasks.map((task) => task.id)).toEqual([7]);

    await act(async () => {
      stale.resolve([SIX, SEVEN]);
      await stale.promise;
    });

    expect(board.tasks.map((task) => task.id)).toEqual([7]);
  });

  it('does not undo a rename made while it was in flight', async () => {
    const stale = await startLiveRefresh();

    await act(async () => {
      await board.updateTaskName(7, 'Renamed');
    });

    await act(async () => {
      stale.resolve([SIX, SEVEN]);
      await stale.promise;
    });

    expect(board.tasks.find((task) => task.id === 7).title).toBe('Renamed');
  });

  it('still applies a refresh when nothing changed locally in the meantime', async () => {
    const stale = await startLiveRefresh();

    await act(async () => {
      stale.resolve([SEVEN]);
      await stale.promise;
    });

    expect(board.tasks.map((task) => task.id)).toEqual([7]);
  });
});
