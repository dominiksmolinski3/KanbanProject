import React from 'react';
import { render, act, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useKanban } from '../../context/KanbanContext';
import { KanbanProvider } from '../../test-utils/KanbanTestProvider';
import * as api from '../../services/api';
import * as boardApi from '../../services/boardApi';
import { toast } from 'react-toastify';

jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn(), info: jest.fn(), warning: jest.fn() },
}));

const watchers = [];
jest.mock('../../services/boardEvents', () => jest.fn().mockImplementation(function BoardEvents() {
  this.watch = jest.fn((boardId, onEvent, onBoardsChanged) => {
    this.boardId = boardId;
    this.onEvent = onEvent;
    this.onBoardsChanged = onBoardsChanged;
  });
  this.stop = jest.fn();
  watchers.push(this);
}));

jest.mock('../../services/api', () => ({
  fetchColumns: jest.fn(() => Promise.resolve([{ id: 'c1', name: 'To Do', position: 0 }])),
  fetchTasks: jest.fn(() => Promise.resolve([])),
  fetchRows: jest.fn(() => Promise.resolve([])),
  updateTaskColumn: jest.fn(), updateTaskRow: jest.fn(), deleteTask: jest.fn(), addTask: jest.fn(),
  addColumn: jest.fn(), addRow: jest.fn(), updateColumnWipLimit: jest.fn(),
  updateRowWipLimit: jest.fn(), deleteColumn: jest.fn(), deleteRow: jest.fn(),
  reorderColumns: jest.fn(), reorderRows: jest.fn(), reorderTasks: jest.fn(),
  ConcurrentModificationError: class extends Error {},
  updateTaskName: jest.fn(), updateRowName: jest.fn(), updateColumnName: jest.fn(),
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

let client;

const Probe = () => {
  useKanban();
  client = useQueryClient();
  return <div data-testid="probe" />;
};

describe('KanbanContext live board sync', () => {
  let unmount;

  beforeEach(async () => {
    jest.useFakeTimers();
    watchers.length = 0;
    jest.clearAllMocks();

    await act(async () => {
      ({ unmount } = render(<KanbanProvider><Probe /></KanbanProvider>));
    });
    await waitFor(() => expect(watchers).toHaveLength(1));
    jest.clearAllMocks();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  const emit = (...events) => act(() => {
    events.forEach((type) => watchers[0].onEvent({ type, boardId: 1 }));
  });

  const settle = async () => {
    await act(async () => {
      jest.advanceTimersByTime(300);
      await Promise.resolve();
      await Promise.resolve();
    });
  };

  it('a boards notice re-reads the boards and the open board', async () => {
    await act(async () => {
      watchers[0].onBoardsChanged();
      await Promise.resolve();
    });
    await settle();

    expect(boardApi.fetchBoards).toHaveBeenCalled();
    expect(api.fetchTasks).toHaveBeenCalled();
    expect(toast.info).not.toHaveBeenCalled();
  });

  it('a boards notice that takes the open board away moves to another and says why', async () => {
    boardApi.fetchBoards.mockResolvedValue([{ id: 2, name: 'Other', ownerId: 1, owned: true, members: [] }]);

    await act(async () => {
      watchers[0].onBoardsChanged();
      await Promise.resolve();
    });
    await settle();

    await waitFor(() => expect(api.setActiveBoardId).toHaveBeenCalledWith(2));
    expect(toast.info).toHaveBeenCalledWith('notifications.boardNoLongerAvailable');
  });

  it('watches the board it resolved on load', () => {
    expect(watchers[0].boardId).toBe(1);
    expect(typeof watchers[0].onEvent).toBe('function');
  });

  it('a task event re-reads the tasks and not the layout', async () => {
    emit('TASKS');
    await settle();

    expect(api.fetchTasks).toHaveBeenCalledTimes(1);
    expect(api.fetchColumns).not.toHaveBeenCalled();
  });

  it('a layout event re-reads the whole board, because a deleted column takes its cards', async () => {
    emit('COLUMNS');
    await settle();

    expect(api.fetchColumns).toHaveBeenCalledTimes(1);
    expect(api.fetchRows).toHaveBeenCalledTimes(1);
  });

  const seed = () => {
    client.setQueryData(['comments', 5], { comments: [], total: 0 });
    client.setQueryData(['subtasks', 5], []);
    client.setQueryData(['attachments', 5], []);
    client.setQueryData(['history', 5], []);
  };
  const stale = (key) => client.getQueryState(key).isInvalidated;

  it('a comment event re-reads no board state and marks the comment threads stale', async () => {
    seed();

    emit('COMMENTS');
    await settle();

    expect(stale(['comments', 5])).toBe(true);
    expect(stale(['subtasks', 5])).toBe(false);
    expect(stale(['attachments', 5])).toBe(false);
    expect(api.fetchTasks).not.toHaveBeenCalled();
    expect(api.fetchColumns).not.toHaveBeenCalled();
  });

  it('a subtask event re-reads the tasks, since the card carries its open count, and marks the open panel subtasks stale', async () => {
    seed();

    emit('SUBTASKS');
    await settle();

    expect(stale(['subtasks', 5])).toBe(true);
    expect(stale(['comments', 5])).toBe(false);
    expect(api.fetchTasks).toHaveBeenCalledTimes(1);
    expect(api.fetchColumns).not.toHaveBeenCalled();
  });

  it('a subtask event in a burst of task events is still one task read', async () => {
    emit('TASKS', 'SUBTASKS', 'TASKS');
    await settle();

    expect(api.fetchTasks).toHaveBeenCalledTimes(1);
    expect(api.fetchColumns).not.toHaveBeenCalled();
  });

  it('an attachment event re-reads no board state and marks the attachment lists stale', async () => {
    seed();

    emit('ATTACHMENTS');
    await settle();

    expect(stale(['attachments', 5])).toBe(true);
    expect(stale(['comments', 5])).toBe(false);
    expect(stale(['history', 5])).toBe(false);
    expect(api.fetchTasks).not.toHaveBeenCalled();
    expect(api.fetchColumns).not.toHaveBeenCalled();
  });

  it('a comment inside a burst of task events does not widen or swallow the task read', async () => {
    emit('TASKS', 'COMMENTS');
    await settle();

    expect(api.fetchTasks).toHaveBeenCalledTimes(1);
    expect(api.fetchColumns).not.toHaveBeenCalled();
  });

  it('nothing is re-read before the window closes', () => {
    emit('TASKS');

    expect(api.fetchTasks).not.toHaveBeenCalled();
  });

  it('a burst of events costs one re-read, which is the point of the window', async () => {
    emit('TASKS', 'TASKS', 'TASKS', 'TASKS');
    await settle();

    expect(api.fetchTasks).toHaveBeenCalledTimes(1);
  });

  it('a layout event inside a burst widens the read rather than being lost in it', async () => {
    emit('TASKS', 'ROWS');
    await settle();

    expect(api.fetchRows).toHaveBeenCalledTimes(1);
  });

  it('a second burst is read again, so the window closes rather than latching', async () => {
    emit('TASKS');
    await settle();
    emit('TASKS');
    await settle();

    expect(api.fetchTasks).toHaveBeenCalledTimes(2);
  });

  it('lets go of the connection when the board screen goes away', () => {
    act(() => unmount());

    expect(watchers[0].stop).toHaveBeenCalled();
  });

  it('a pending re-read does not fire after unmount', async () => {
    emit('TASKS');
    act(() => unmount());
    await settle();

    expect(api.fetchTasks).not.toHaveBeenCalled();
  });
});
