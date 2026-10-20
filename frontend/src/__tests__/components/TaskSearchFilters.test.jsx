import React from 'react';
import { render, screen, fireEvent, waitFor } from '../../test-utils/rtl';
import '@testing-library/jest-dom';
import KanbanContext from '../../context/KanbanContext';
import TaskSearch from '../../components/TaskSearch';
import * as api from '../../services/api';

jest.mock('../../services/api', () => ({
  searchTasks: jest.fn(),
  getAllLabels: jest.fn(),
  fetchUsers: jest.fn(),
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, options) => (options ? `${key}:${JSON.stringify(options)}` : key),
  }),
}));

const WAIT = { timeout: 3000 };

const context = { columns: [{ id: 1, name: 'To Do' }], rows: [{ id: 5, name: 'Features' }], tasks: [{ id: 9 }] };

const answer = (tasks, extra = {}) => ({
  tasks, page: 0, size: 25, totalTasks: tasks.length, totalPages: tasks.length ? 1 : 0, ...extra,
});

const task = (id, extra = {}) => ({ id, title: `Task ${id}`, columnId: 1, rowId: 5, labels: [], userIds: [], ...extra });

const lastRequest = () => api.searchTasks.mock.calls.at(-1)[0];

const open = async () => {
  render(
    <KanbanContext.Provider value={context}>
      <TaskSearch />
    </KanbanContext.Provider>
  );
  fireEvent.click(screen.getByTestId('open-task-search'));
  await waitFor(() => expect(api.searchTasks).toHaveBeenCalled(), WAIT);
};

const settled = (expected) => waitFor(() => expect(lastRequest()).toMatchObject(expected), WAIT);

describe('TaskSearch filters', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.getAllLabels.mockResolvedValue(['bug', 'ux']);
    api.fetchUsers.mockResolvedValue([{ id: 3, name: 'Ada' }, { id: 4, email: 'grace@example.com' }]);
    api.searchTasks.mockResolvedValue(answer([]));
  });

  test('a label chip filters by that label and a second press takes it off', async () => {
    await open();

    fireEvent.click(await screen.findByRole('button', { name: 'bug' }));
    expect(screen.getByRole('button', { name: 'bug' })).toHaveAttribute('aria-pressed', 'true');
    await settled({ labels: ['bug'] });

    fireEvent.click(screen.getByRole('button', { name: 'bug' }));
    expect(screen.getByRole('button', { name: 'bug' })).toHaveAttribute('aria-pressed', 'false');
    await settled({ labels: [] });
  });

  test('several chips combine, and an assignee chip filters by that person\'s id', async () => {
    await open();

    fireEvent.click(await screen.findByRole('button', { name: 'bug' }));
    fireEvent.click(screen.getByRole('button', { name: 'ux' }));
    fireEvent.click(screen.getByRole('button', { name: 'Ada' }));

    await settled({ labels: ['bug', 'ux'], assignees: [3] });
  });

  test('a person with no name is offered by their email', async () => {
    await open();

    expect(await screen.findByRole('button', { name: 'grace@example.com' })).toBeInTheDocument();
  });

  test('the deadline fields are sent as the instants the local times name, and as null when empty', async () => {
    await open();
    expect(lastRequest()).toMatchObject({ deadlineFrom: null, deadlineTo: null });

    const [from, to] = document.querySelectorAll('input[type="datetime-local"]');
    fireEvent.change(from, { target: { value: '2026-10-01T09:00' } });
    fireEvent.change(to, { target: { value: '2026-10-31T17:30' } });

    await settled({
      deadlineFrom: new Date('2026-10-01T09:00').toISOString(),
      deadlineTo: new Date('2026-10-31T17:30').toISOString(),
    });
  });

  test('there is nothing to clear until a filter is set, and clearing puts every filter back', async () => {
    await open();
    expect(screen.queryByText('board.search.clear')).not.toBeInTheDocument();

    fireEvent.change(screen.getByTestId('task-search-term'), { target: { value: 'ship' } });
    fireEvent.click(await screen.findByRole('button', { name: 'bug' }));
    fireEvent.click(screen.getByRole('button', { name: 'Ada' }));
    fireEvent.change(screen.getByTestId('task-search-status'), { target: { value: 'true' } });
    await settled({ q: 'ship', labels: ['bug'], assignees: [3], completed: true });

    fireEvent.click(screen.getByText('board.search.clear'));

    await settled({ q: '', labels: [], assignees: [], completed: null, deadlineFrom: null, deadlineTo: null });
    expect(screen.getByTestId('task-search-term')).toHaveValue('');
    expect(screen.queryByText('board.search.clear')).not.toBeInTheDocument();
  });

  test('a filter that was set before the page was turned sends the user back to page one', async () => {
    api.searchTasks.mockResolvedValue(answer([task(1)], { totalTasks: 60, totalPages: 3 }));
    await open();
    await screen.findByText('Task 1');
    fireEvent.click(screen.getByText('board.search.next'));
    await settled({ page: 1 });

    fireEvent.click(await screen.findByRole('button', { name: 'ux' }));

    await settled({ page: 0, labels: ['ux'] });
  });

  test('the labels and people could not be loaded: the search still works, without chips', async () => {
    api.getAllLabels.mockRejectedValue(new Error('down'));
    api.searchTasks.mockResolvedValue(answer([task(1)]));
    await open();

    expect(await screen.findByText('Task 1')).toBeInTheDocument();
    expect(screen.queryByText('board.search.labels')).not.toBeInTheDocument();
    expect(screen.queryByText('board.search.assignees')).not.toBeInTheDocument();
  });

  test('a person who is no longer on the board is shown by number', async () => {
    api.searchTasks.mockResolvedValue(answer([task(1, { userIds: [3, 99] })]));
    await open();

    expect(await screen.findByText('Ada', { selector: '.task-search-result-user' })).toBeInTheDocument();
    expect(screen.getByText('#99')).toBeInTheDocument();
  });
});

describe('TaskSearch paging and the empty result', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.getAllLabels.mockResolvedValue([]);
    api.fetchUsers.mockResolvedValue([]);
  });

  test('walks forward and back, and the ends are disabled', async () => {
    api.searchTasks.mockResolvedValue(answer([task(1)], { totalTasks: 60, totalPages: 3 }));
    await open();
    await screen.findByText('Task 1');
    expect(screen.getByText('board.search.previous')).toBeDisabled();
    expect(screen.getByText(/board.search.pageOf/)).toHaveTextContent('"page":1,"pages":3');

    fireEvent.click(screen.getByText('board.search.next'));
    await settled({ page: 1 });
    fireEvent.click(screen.getByText('board.search.next'));
    await settled({ page: 2 });
    await waitFor(() => expect(screen.getByText('board.search.next')).toBeDisabled(), WAIT);

    fireEvent.click(screen.getByText('board.search.previous'));
    await settled({ page: 1 });
  });

  test('one page has no pager', async () => {
    api.searchTasks.mockResolvedValue(answer([task(1)]));
    await open();
    await screen.findByText('Task 1');

    expect(screen.queryByText('board.search.next')).not.toBeInTheDocument();
  });

  test('no match on a board that does have cards says how many it has', async () => {
    api.searchTasks.mockResolvedValue(answer([]));
    await open();

    expect(await screen.findByText('board.search.empty')).toBeInTheDocument();
    expect(screen.getByText(/board.search.boardHas/)).toHaveTextContent('"count":1');
  });

  test('closing the panel puts the button back', async () => {
    api.searchTasks.mockResolvedValue(answer([task(1)]));
    await open();

    fireEvent.click(screen.getByLabelText('board.search.close'));

    expect(screen.getByTestId('open-task-search')).toBeInTheDocument();
    expect(screen.queryByTestId('task-search')).not.toBeInTheDocument();
  });
});
