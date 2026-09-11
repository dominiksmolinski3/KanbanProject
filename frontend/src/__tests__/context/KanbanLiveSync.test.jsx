import React from 'react';
import { render, act, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { KanbanProvider, useKanban } from '../../context/KanbanContext';
import * as api from '../../services/api';

/*
 * The service is replaced rather than the STOMP client, because what this suite is about is the
 * context's half of the contract: which re-read answers which kind of event, and how many
 * re-reads a burst costs. `boardEvents.test.js` covers the connection itself.
 */
const watchers = [];
jest.mock('../../services/boardEvents', () => jest.fn().mockImplementation(function BoardEvents() {
  this.watch = jest.fn((boardId, onEvent) => {
    this.boardId = boardId;
    this.onEvent = onEvent;
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

const Probe = () => {
  useKanban();
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

  // `refreshBoard` awaits two fetches, so advancing the clock is only the first half of letting
  // the re-read happen; the microtasks behind it have to run too.
  const settle = async () => {
    await act(async () => {
      jest.advanceTimersByTime(300);
      await Promise.resolve();
      await Promise.resolve();
    });
  };

  it('watches the board it resolved on load', () => {
    // Read off the instance rather than the call record: the harness clears mock calls once the
    // provider has settled, and the subscription happens during that setup.
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

  it('nothing is re-read before the window closes', () => {
    emit('TASKS');

    expect(api.fetchTasks).not.toHaveBeenCalled();
  });

  it('a burst of events costs one re-read, which is the point of the window', async () => {
    // What a single drag produces: the task moved, and its cell renumbered.
    emit('TASKS', 'TASKS', 'TASKS', 'TASKS');
    await settle();

    expect(api.fetchTasks).toHaveBeenCalledTimes(1);
  });

  it('a layout event inside a burst widens the read rather than being lost in it', async () => {
    emit('TASKS', 'ROWS');
    await settle();

    // The wider read is the one that covers both; taking the first event's answer would leave a
    // deleted swimlane on screen.
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
