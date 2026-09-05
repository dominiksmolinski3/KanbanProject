import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import KanbanContext from '../../context/KanbanContext';
import TaskSearch from '../../components/TaskSearch';
import * as api from '../../services/api';

jest.mock('../../services/api', () => ({
  searchTasks: jest.fn(),
  getAllLabels: jest.fn(),
  fetchUsers: jest.fn()
}));

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, options) =>
      options ? `${key}:${JSON.stringify(options)}` : key
  })
}));

/**
 * The panel's behaviour, and the three things about a search box that are always the bugs.
 *
 * <b>The debounce</b>, because a request per keystroke is what an unguarded search-as-you-type is.
 * <b>The page reset</b>, because changing a filter while on page three shows an empty list for a
 * search that matched plenty, and reads as "nothing found". And <b>the out-of-order answer</b>,
 * because a slow response to an old query landing after a fast response to the current one leaves
 * the list showing results nobody asked for and no error anywhere.
 */
describe('TaskSearch', () => {
  const context = {
    columns: [{ id: 1, name: 'To Do' }],
    rows: [{ id: 5, name: 'Features' }],
    tasks: [{ id: 9 }]
  };

  const page = (tasks, extra = {}) => ({
    tasks,
    page: 0,
    size: 25,
    totalTasks: tasks.length,
    totalPages: tasks.length ? 1 : 0,
    ...extra
  });

  const renderPanel = () =>
    render(
      <KanbanContext.Provider value={context}>
        <TaskSearch />
      </KanbanContext.Provider>
    );

  const open = async () => {
    renderPanel();
    fireEvent.click(screen.getByTestId('open-task-search'));
    await waitFor(() => expect(api.searchTasks).toHaveBeenCalled(), { timeout: 3000 });
  };

  beforeEach(() => {
    jest.clearAllMocks();
    api.getAllLabels.mockResolvedValue(['bug', 'ux']);
    api.fetchUsers.mockResolvedValue([{ id: 3, name: 'Ada' }]);
    api.searchTasks.mockResolvedValue(page([]));
  });

  test('nothing is requested until the panel is opened', () => {
    renderPanel();

    expect(api.searchTasks).not.toHaveBeenCalled();
    expect(api.getAllLabels).not.toHaveBeenCalled();
  });

  test('opening it runs an empty search, which is the board a page at a time', async () => {
    await open();

    expect(api.searchTasks).toHaveBeenCalledWith(
      expect.objectContaining({ q: '', page: 0, labels: [], assignees: [] })
    );
  });

  test('typing is debounced into one request rather than one per keystroke', async () => {
    await open();
    api.searchTasks.mockClear();

    const box = screen.getByTestId('task-search-term');
    fireEvent.change(box, { target: { value: 'd' } });
    fireEvent.change(box, { target: { value: 'de' } });
    fireEvent.change(box, { target: { value: 'dep' } });

    await waitFor(() => expect(api.searchTasks).toHaveBeenCalledTimes(1), { timeout: 3000 });
    expect(api.searchTasks).toHaveBeenCalledWith(expect.objectContaining({ q: 'dep' }));
  });

  test('the results are listed with the cell each task sits in', async () => {
    api.searchTasks.mockResolvedValue(
      page([{ id: 9, title: 'Ship the thing', columnId: 1, rowId: 5, labels: [], userIds: [] }])
    );

    await open();

    expect(await screen.findByText('Ship the thing')).toBeInTheDocument();
    expect(screen.getByText('To Do · Features')).toBeInTheDocument();
  });

  test('a task in no column says so rather than showing a blank', async () => {
    api.searchTasks.mockResolvedValue(
      page([{ id: 9, title: 'Loose task', columnId: null, rowId: null, labels: [], userIds: [] }])
    );

    await open();

    expect(await screen.findByText('board.search.noColumn · board.search.noRow')).toBeInTheDocument();
  });

  test('changing a filter goes back to the first page', async () => {
    api.searchTasks.mockResolvedValue(
      page([{ id: 9, title: 'One', columnId: 1, rowId: 5, labels: [], userIds: [] }], {
        totalTasks: 90,
        totalPages: 4
      })
    );
    await open();
    await screen.findByText('One');

    fireEvent.click(screen.getByText('board.search.next'));
    await waitFor(() => expect(api.searchTasks).toHaveBeenCalledWith(
      expect.objectContaining({ page: 1 })
    ), { timeout: 3000 });
    api.searchTasks.mockClear();

    fireEvent.change(screen.getByTestId('task-search-term'), { target: { value: 'ship' } });

    await waitFor(() => expect(api.searchTasks).toHaveBeenCalledWith(
      expect.objectContaining({ q: 'ship', page: 0 })
    ), { timeout: 3000 });
  });

  test('a slow answer to an old query does not overwrite the current one', async () => {
    await open();

    let releaseStale;
    const stale = new Promise((resolve) => {
      releaseStale = () => resolve(page([{ id: 1, title: 'STALE', labels: [], userIds: [] }]));
    });
    api.searchTasks.mockReturnValueOnce(stale);

    fireEvent.change(screen.getByTestId('task-search-term'), { target: { value: 'de' } });
    await waitFor(() => expect(api.searchTasks).toHaveBeenCalledWith(
      expect.objectContaining({ q: 'de' })
    ), { timeout: 3000 });

    api.searchTasks.mockResolvedValue(page([{ id: 2, title: 'CURRENT', labels: [], userIds: [] }]));
    fireEvent.change(screen.getByTestId('task-search-term'), { target: { value: 'deploy' } });
    expect(await screen.findByText('CURRENT', {}, { timeout: 3000 })).toBeInTheDocument();

    releaseStale();
    await new Promise((resolve) => setTimeout(resolve, 50));

    expect(screen.queryByText('STALE')).not.toBeInTheDocument();
    expect(screen.getByText('CURRENT')).toBeInTheDocument();
  });

  test('a status filter is sent as a boolean, not as the select box string', async () => {
    await open();
    api.searchTasks.mockClear();

    fireEvent.change(screen.getByTestId('task-search-status'), { target: { value: 'true' } });

    await waitFor(() => expect(api.searchTasks).toHaveBeenCalledWith(
      expect.objectContaining({ completed: true })
    ), { timeout: 3000 });
  });

  test('a failed search says so instead of showing an empty result list', async () => {
    api.searchTasks.mockRejectedValue(new Error('nope'));

    await open();

    expect(await screen.findByText('board.search.error')).toBeInTheDocument();
    expect(screen.queryByText('board.search.empty')).not.toBeInTheDocument();
  });

  test('"show on board" reports a card that is not rendered rather than doing nothing', async () => {
    api.searchTasks.mockResolvedValue(
      page([{ id: 9, title: 'Hidden', columnId: 1, rowId: 5, labels: [], userIds: [] }])
    );
    await open();
    await screen.findByText('Hidden');

    fireEvent.click(screen.getByText('board.search.showOnBoard'));

    expect(await screen.findByText('board.search.notOnBoard')).toBeInTheDocument();
  });

  test('"show on board" scrolls to the real card when there is one', async () => {
    const card = document.createElement('div');
    card.id = 'task-9';
    card.scrollIntoView = jest.fn();
    document.body.appendChild(card);

    api.searchTasks.mockResolvedValue(
      page([{ id: 9, title: 'Visible', columnId: 1, rowId: 5, labels: [], userIds: [] }])
    );
    await open();
    await screen.findByText('Visible');

    fireEvent.click(screen.getByText('board.search.showOnBoard'));

    expect(card.scrollIntoView).toHaveBeenCalled();
    expect(card.classList.contains('task-search-hit')).toBe(true);
    document.body.removeChild(card);
  });
});
