import React from 'react';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import ActivityFeed from '../../components/ActivityFeed';
import { fetchActivity } from '../../services/activityApi';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, values) => (values ? `${key}:${JSON.stringify(values)}` : key)
  })
}));

jest.mock('../../services/activityApi', () => ({
  fetchActivity: jest.fn(),
  DEFAULT_PAGE_SIZE: 25
}));

const mockKanban = { activeBoardId: 3 };

jest.mock('../../context/KanbanContext', () => ({
  useKanban: () => mockKanban
}));

const entry = (overrides = {}) => ({
  id: 1,
  taskId: 5,
  taskTitle: 'Ship the release',
  actorId: 1,
  actorName: 'Ada',
  type: 'MOVED',
  detail: 'In Progress',
  occurredAt: '2026-09-01T10:00:00',
  ...overrides
});

const page = (activities, overrides = {}) => ({
  activities,
  page: 0,
  size: 25,
  totalEntries: activities.length,
  totalPages: 1,
  ...overrides
});

const renderFeed = async () => {
  await act(async () => {
    render(<ActivityFeed />);
  });
};

describe('ActivityFeed', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockKanban.activeBoardId = 3;
    fetchActivity.mockResolvedValue(page([entry()]));
  });

  test('asks for the active board and renders one sentence per entry', async () => {
    await renderFeed();

    expect(fetchActivity).toHaveBeenCalledWith({ boardId: 3, page: 0 });
    // The wording is a translation key with the facts as arguments: the server sends a type name
    // and a detail, never a phrase, so the feed reads in whichever of nine languages is showing.
    expect(screen.getByText(/activity\.types\.MOVED/)).toHaveTextContent('Ada');
    expect(screen.getByText(/activity\.types\.MOVED/)).toHaveTextContent('In Progress');
  });

  test('an entry whose task is gone still reads, and an actorless one names nobody in particular', async () => {
    fetchActivity.mockResolvedValue(page([
      entry({ id: 2, type: 'DELETED', taskId: null, detail: null, actorName: null })
    ]));
    await renderFeed();

    const line = screen.getByText(/activity\.types\.DELETED/);
    expect(line).toHaveTextContent('Ship the release');
    expect(line).toHaveTextContent('activity.someone');
  });

  test('an untitled task gets a stand-in rather than an empty sentence', async () => {
    fetchActivity.mockResolvedValue(page([entry({ taskTitle: '' })]));
    await renderFeed();

    expect(screen.getByText(/activity\.types\.MOVED/)).toHaveTextContent('activity.untitledTask');
  });

  test('paging is hidden on a single page and asks for the next one when it is not', async () => {
    fetchActivity.mockResolvedValue(page([entry()], { totalPages: 3, totalEntries: 60 }));
    await renderFeed();

    fireEvent.click(screen.getByText('activity.next'));

    await waitFor(() =>
      expect(fetchActivity).toHaveBeenLastCalledWith({ boardId: 3, page: 1 }));
  });

  test('a single page shows no paging controls at all', async () => {
    await renderFeed();

    expect(screen.queryByText('activity.next')).not.toBeInTheDocument();
  });

  test('an empty board says so rather than rendering an empty list', async () => {
    fetchActivity.mockResolvedValue(page([]));
    await renderFeed();

    expect(screen.getByText('activity.empty')).toBeInTheDocument();
  });

  test('a refused request is reported rather than left as a blank page', async () => {
    jest.spyOn(console, 'error').mockImplementation(() => {});
    fetchActivity.mockRejectedValue(new Error('Error fetching the activity feed: 403'));
    await renderFeed();

    expect(screen.getByText('activity.failed')).toBeInTheDocument();
    console.error.mockRestore();
  });
});
