import React from 'react';
import { render, act, waitFor, fireEvent } from '@testing-library/react';
import { KanbanProvider } from '../../test-utils/KanbanTestProvider';
import Board from '../../components/Board';

jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn(), info: jest.fn(), warning: jest.fn() },
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key, i18n: { language: 'en' } }),
}));

const watchers = [];
jest.mock('../../services/boardEvents', () => jest.fn().mockImplementation(function BoardEvents() {
  this.watch = jest.fn((boardId, onEvent) => { this.onEvent = onEvent; });
  this.stop = jest.fn();
  watchers.push(this);
}));

jest.mock('../../services/api', () => ({
  ...jest.requireActual('../../services/api'),
  fetchColumns: jest.fn(), fetchTasks: jest.fn(), fetchRows: jest.fn(),
  getActiveBoardId: jest.fn(() => null), setActiveBoardId: jest.fn(),
  updateTaskCompletion: jest.fn(() => Promise.resolve({})),
}));
jest.mock('../../services/boardApi', () => ({
  ...jest.requireActual('../../services/boardApi'),
  fetchBoards: jest.fn(), fetchCurrentBoard: jest.fn(), fetchMyInvitations: jest.fn(() => Promise.resolve([])),
}));

const drawn = [];
jest.mock('../../components/TaskCardMeta', () => ({
  __esModule: true,
  default: ({ task }) => {
    drawn.push(task.id);
    return null;
  },
}));

const api = jest.requireMock('../../services/api');
const boardApi = jest.requireMock('../../services/boardApi');

const BOARD = { id: 1, name: 'Kanban', ownerId: 1, owned: true, role: 'OWNER', members: [] };
const COLUMNS = [{ id: 1, name: 'To do', position: 0 }, { id: 2, name: 'Done', position: 1 }];
const ROWS = [{ id: 1, name: 'Lane', position: 0 }];
const TASKS = Array.from({ length: 12 }, (_, i) => ({
  id: i + 1, title: `Task ${i + 1}`, columnId: (i % 2) + 1, rowId: 1, position: i,
  completed: false, userIds: [], labels: [], version: 0,
  childTaskIds: i === 0 ? [2] : [], parentTaskId: i === 1 ? 1 : null,
}));
const copy = (value) => JSON.parse(JSON.stringify(value));

describe('how much of the board one change redraws', () => {
  let container;
  const settle = () => act(async () => { await new Promise((resolve) => setTimeout(resolve, 400)); });
  const dataTransfer = () => ({ setData: jest.fn(), getData: jest.fn(), types: [], setDragImage: jest.fn() });
  const card = (id) => container.querySelector(`#task-${id}`);

  beforeEach(async () => {
    watchers.length = 0;
    api.fetchColumns.mockImplementation(() => Promise.resolve(copy(COLUMNS)));
    api.fetchRows.mockImplementation(() => Promise.resolve(copy(ROWS)));
    api.fetchTasks.mockImplementation(() => Promise.resolve(copy(TASKS)));
    boardApi.fetchBoards.mockResolvedValue([BOARD]);
    boardApi.fetchCurrentBoard.mockResolvedValue(BOARD);

    ({ container } = render(<KanbanProvider><Board /></KanbanProvider>));
    await waitFor(() => expect(container.querySelectorAll('.task')).toHaveLength(12));
    await settle();
    drawn.length = 0;
  });

  it('draws every card once to begin with, so the counts below mean something', async () => {
    const { container: second } = render(<KanbanProvider><Board /></KanbanProvider>);
    await waitFor(() => expect(second.querySelectorAll('.task')).toHaveLength(12));

    expect(new Set(drawn).size).toBe(12);
  });

  it('picking a card up redraws it and the cards linked to it, not the board', async () => {
    await act(async () => { fireEvent.dragStart(card(1), { dataTransfer: dataTransfer() }); });
    await settle();

    expect(card(2)).toHaveClass('linked-child');
    expect([...new Set(drawn)].sort()).toEqual([1, 2]);
  });

  it('putting it down redraws the same two', async () => {
    await act(async () => { fireEvent.dragStart(card(1), { dataTransfer: dataTransfer() }); });
    await settle();
    drawn.length = 0;

    await act(async () => { fireEvent.dragEnd(card(1), { dataTransfer: dataTransfer() }); });
    await settle();

    expect(card(2)).not.toHaveClass('linked-child');
    expect([...new Set(drawn)].sort()).toEqual([1, 2]);
  });

  it('a live event that changed one card redraws that card', async () => {
    const changed = copy(TASKS);
    changed[6].title = 'Renamed';
    api.fetchTasks.mockImplementation(() => Promise.resolve(copy(changed)));

    await act(async () => { watchers[0].onEvent({ type: 'TASKS', boardId: 1 }); });
    await settle();

    expect(card(7)).toHaveTextContent('Renamed');
    expect([...new Set(drawn)]).toEqual([7]);
  });

  it('ticking one card complete redraws that card', async () => {
    api.updateTaskCompletion.mockResolvedValue({ completed: true });

    await act(async () => { fireEvent.click(card(4).querySelector('.task-complete-checkbox')); });
    await settle();

    expect(card(4)).toHaveClass('task-completed');
    expect([...new Set(drawn)]).toEqual([4]);
  });
});
