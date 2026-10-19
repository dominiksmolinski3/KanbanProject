import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import KanbanContext from '../../context/KanbanContext';
import { createQueryClient } from '../../services/queryClient';
import { useBenchUsers } from '../../components/bench/useBenchUsers';
import { usePeople } from '../../components/people/usePeople';
import { useTaskSearch } from '../../components/search/useTaskSearch';
import * as api from '../../services/api';
import { toast } from 'react-toastify';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));

jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn(), info: jest.fn(), warning: jest.fn() },
}));

jest.mock('../../services/api', () => ({
  fetchUsers: jest.fn(),
  getUserAvatar: jest.fn(),
  updateUserWipLimit: jest.fn(),
  deleteUser: jest.fn(),
  uploadUserAvatar: jest.fn(),
  searchTasks: jest.fn(),
}));

const refreshTasks = jest.fn();
const ADA = { id: 1, name: 'Ada', wipLimit: 3 };
const BOB = { id: 2, name: 'Bob', wipLimit: 5 };

describe('the users read by the bench and the people list', () => {
  let client;
  const wrapper = ({ children }) => (
    <QueryClientProvider client={client}>
      <KanbanContext.Provider value={{ refreshTasks }}>{children}</KanbanContext.Provider>
    </QueryClientProvider>
  );

  beforeEach(() => {
    jest.clearAllMocks();
    console.error = jest.fn();
    client = createQueryClient();
    api.fetchUsers.mockResolvedValue([ADA, BOB]);
    api.getUserAvatar.mockResolvedValue(null);
    api.updateUserWipLimit.mockResolvedValue({});
    URL.revokeObjectURL = jest.fn();
  });

  it('is one read when the bench and the people list are both on screen', async () => {
    const bench = renderHook(() => useBenchUsers(), { wrapper });
    const people = renderHook(() => usePeople(), { wrapper });

    await waitFor(() => expect(bench.result.current.users).toHaveLength(2));
    await waitFor(() => expect(people.result.current.users).toHaveLength(2));

    expect(api.fetchUsers).toHaveBeenCalledTimes(1);
  });

  it('asks for each avatar once', async () => {
    const { result } = renderHook(() => useBenchUsers(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => {});

    expect(api.getUserAvatar).toHaveBeenCalledTimes(2);
    expect(api.getUserAvatar).toHaveBeenCalledWith(1);
    expect(api.getUserAvatar).toHaveBeenCalledWith(2);
  });

  it('shows an avatar once it has arrived', async () => {
    api.getUserAvatar.mockImplementation(async (id) => (id === 1 ? 'blob:ada' : null));

    const { result } = renderHook(() => useBenchUsers(), { wrapper });

    await waitFor(() => expect(result.current.avatars).toEqual({ 1: 'blob:ada' }));
  });

  it('gives its avatars back when the bench goes away', async () => {
    api.getUserAvatar.mockImplementation(async (id) => `blob:user-${id}`);
    const { result, unmount } = renderHook(() => useBenchUsers(), { wrapper });
    await waitFor(() => expect(Object.keys(result.current.avatars)).toHaveLength(2));

    unmount();

    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:user-1');
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:user-2');
  });

  it('saving a limit changes that user in the list and leaves the avatars alone', async () => {
    const { result } = renderHook(() => useBenchUsers(), { wrapper });
    await waitFor(() => expect(result.current.users).toHaveLength(2));
    await act(async () => {});
    api.getUserAvatar.mockClear();

    let saved;
    await act(async () => {
      saved = await result.current.saveWipLimit(1, 9);
    });

    expect(saved).toBe(true);
    expect(api.updateUserWipLimit).toHaveBeenCalledWith(1, 9);
    expect(result.current.users.map((u) => u.wipLimit)).toEqual([9, 5]);
    expect(api.getUserAvatar).not.toHaveBeenCalled();
    expect(api.fetchUsers).toHaveBeenCalledTimes(1);
    expect(refreshTasks).toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalled();
  });

  it('a limit the server refused leaves the list as it was', async () => {
    api.updateUserWipLimit.mockRejectedValue(new Error('no'));
    const { result } = renderHook(() => useBenchUsers(), { wrapper });
    await waitFor(() => expect(result.current.users).toHaveLength(2));

    let saved;
    await act(async () => {
      saved = await result.current.saveWipLimit(1, 9);
    });

    expect(saved).toBe(false);
    expect(result.current.users.map((u) => u.wipLimit)).toEqual([3, 5]);
    expect(toast.error).toHaveBeenCalled();
  });

  it('says so, and stops loading, when the users cannot be read', async () => {
    api.fetchUsers.mockRejectedValue(new Error('users are down'));

    const { result } = renderHook(() => useBenchUsers(), { wrapper });

    await waitFor(() => expect(result.current.error).toBe('bench.loadError'));
    expect(result.current.loading).toBe(false);
    expect(toast.error).toHaveBeenCalledWith('notifications.usersLoadError');
  });

  it('removing a person drops them from every screen that shows the users', async () => {
    window.confirm = jest.fn(() => true);
    api.deleteUser.mockResolvedValue(undefined);
    const bench = renderHook(() => useBenchUsers(), { wrapper });
    const people = renderHook(() => usePeople(), { wrapper });
    await waitFor(() => expect(people.result.current.users).toHaveLength(2));

    await act(async () => {
      await people.result.current.removeUser(1);
    });

    expect(people.result.current.users.map((u) => u.id)).toEqual([2]);
    expect(bench.result.current.users.map((u) => u.id)).toEqual([2]);
  });

  it('keeps a person whose removal was not confirmed or was refused', async () => {
    const { result } = renderHook(() => usePeople(), { wrapper });
    await waitFor(() => expect(result.current.users).toHaveLength(2));

    window.confirm = jest.fn(() => false);
    await act(async () => {
      await result.current.removeUser(1);
    });
    expect(api.deleteUser).not.toHaveBeenCalled();

    window.confirm = jest.fn(() => true);
    api.deleteUser.mockRejectedValue(new Error('no'));
    await act(async () => {
      await result.current.removeUser(1);
    });
    expect(result.current.users).toHaveLength(2);
    expect(toast.error).toHaveBeenCalledWith('usersManagement.messages.deleteError');
  });

  it('says so when the people cannot be read', async () => {
    api.fetchUsers.mockRejectedValue(new Error('down'));

    const { result } = renderHook(() => usePeople(), { wrapper });

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('usersManagement.messages.loadError'));
    expect(result.current.users).toEqual([]);
  });
});

describe('the task search read', () => {
  let client;
  const wrapper = ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;

  const filters = (overrides = {}) => ({
    term: '', labels: [], assignees: [], completed: '', deadlineFrom: '', deadlineTo: '', page: 0, ...overrides,
  });
  const answer = (...ids) => ({ results: ids.map((id) => ({ id })), totalEntries: ids.length, totalPages: 1 });

  beforeEach(() => {
    jest.useFakeTimers();
    jest.clearAllMocks();
    client = createQueryClient();
    api.searchTasks.mockResolvedValue(answer(1));
  });

  afterEach(() => jest.useRealTimers());

  const settle = () => act(async () => {
    jest.advanceTimersByTime(400);
  });

  it('asks nothing while the panel is closed', async () => {
    renderHook(() => useTaskSearch(false, filters()), { wrapper });

    await settle();

    expect(api.searchTasks).not.toHaveBeenCalled();
  });

  it('sends the filters in the shape the API takes', async () => {
    renderHook(() => useTaskSearch(true, filters({
      term: 'fix', labels: ['bug'], assignees: [3], completed: 'true', deadlineFrom: '2026-01-01', page: 2,
    })), { wrapper });

    await settle();

    expect(api.searchTasks).toHaveBeenCalledWith({
      q: 'fix', labels: ['bug'], assignees: [3], completed: true, deadlineFrom: '2026-01-01', deadlineTo: null, page: 2,
    });
  });

  it('keeps the last results on screen while the next search is running', async () => {
    const { result, rerender } = renderHook(({ term }) => useTaskSearch(true, filters({ term })), {
      wrapper, initialProps: { term: '' },
    });
    await settle();
    await waitFor(() => expect(result.current.results.results.map((r) => r.id)).toEqual([1]));

    let finish;
    api.searchTasks.mockReturnValueOnce(new Promise((resolve) => { finish = resolve; }));
    rerender({ term: 'x' });
    await settle();

    expect(result.current.isSearching).toBe(true);
    expect(result.current.results.results.map((r) => r.id)).toEqual([1]);

    await act(async () => {
      finish(answer(2));
    });
    expect(result.current.isSearching).toBe(false);
    expect(result.current.results.results.map((r) => r.id)).toEqual([2]);
  });

  it('is not searching while a burst of typing is still settling', async () => {
    const { result, rerender } = renderHook(({ term }) => useTaskSearch(true, filters({ term })), {
      wrapper, initialProps: { term: '' },
    });
    await settle();
    api.searchTasks.mockClear();

    rerender({ term: 'a' });
    act(() => jest.advanceTimersByTime(100));
    rerender({ term: 'ab' });
    act(() => jest.advanceTimersByTime(100));
    rerender({ term: 'abc' });
    expect(result.current.isSearching).toBe(false);
    expect(api.searchTasks).not.toHaveBeenCalled();

    await settle();
    expect(api.searchTasks).toHaveBeenCalledTimes(1);
    expect(api.searchTasks).toHaveBeenCalledWith(expect.objectContaining({ q: 'abc' }));
  });

  it('reports a search that failed, with no results', async () => {
    api.searchTasks.mockRejectedValue(new Error('500'));

    const { result } = renderHook(() => useTaskSearch(true, filters()), { wrapper });
    await settle();

    await waitFor(() => expect(result.current.failed).toBe(true));
    expect(result.current.results).toBeNull();
  });
});
