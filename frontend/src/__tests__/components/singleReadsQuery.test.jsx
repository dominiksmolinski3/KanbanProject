import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { createQueryClient } from '../../services/queryClient';
import { useActivityFeed } from '../../components/activity/useActivityFeed';
import { usePendingInvitations } from '../../components/boardMembers/usePendingInvitations';
import { fetchActivity } from '../../services/activityApi';
import { fetchBoardInvitations } from '../../services/boardApi';

jest.mock('../../services/activityApi', () => ({ fetchActivity: jest.fn() }));
jest.mock('../../services/boardApi', () => ({ fetchBoardInvitations: jest.fn() }));

const wrap = (client) => ({ children }) => (
  <QueryClientProvider client={client}>{children}</QueryClientProvider>
);

const feedPage = (page, ids, totalPages = 3) => ({
  activities: ids.map((id) => ({ id, type: 'MOVED' })),
  page,
  totalEntries: 30,
  totalPages,
});

describe('useActivityFeed', () => {
  let client;

  beforeEach(() => {
    jest.clearAllMocks();
    console.error = jest.fn();
    client = createQueryClient();
    fetchActivity.mockImplementation(async ({ page }) => feedPage(page, [page * 10 + 1, page * 10 + 2]));
  });

  it('reads the board it was given, a page at a time', async () => {
    const { result } = renderHook(() => useActivityFeed(3), { wrapper: wrap(client) });

    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(fetchActivity).toHaveBeenCalledWith({ boardId: 3, page: 0 });
    expect(result.current.entries.map((e) => e.id)).toEqual([1, 2]);
    expect(result.current.totalPages).toBe(3);
    expect(result.current.total).toBe(30);
  });

  it('shows the loading state again while another page is read, and not for one it has', async () => {
    const { result } = renderHook(() => useActivityFeed(3), { wrapper: wrap(client) });
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => result.current.setPage(1));
    expect(result.current.page).toBe(1);
    expect(result.current.loading).toBe(true);
    await waitFor(() => expect(result.current.entries.map((e) => e.id)).toEqual([11, 12]));

    act(() => result.current.setPage(0));
    expect(result.current.loading).toBe(false);
    expect(result.current.entries.map((e) => e.id)).toEqual([1, 2]);
  });

  it('goes back to the first page when the board changes, without reading the old page of the new board', async () => {
    const { result, rerender } = renderHook(({ board }) => useActivityFeed(board), {
      wrapper: wrap(client),
      initialProps: { board: 3 },
    });
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => result.current.setPage(2));
    await waitFor(() => expect(result.current.entries.map((e) => e.id)).toEqual([21, 22]));
    fetchActivity.mockClear();

    rerender({ board: 4 });
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.page).toBe(0);
    expect(fetchActivity).toHaveBeenCalledTimes(1);
    expect(fetchActivity).toHaveBeenCalledWith({ boardId: 4, page: 0 });
  });

  it('reports a read that failed and shows no entries', async () => {
    fetchActivity.mockRejectedValue(new Error('down'));

    const { result } = renderHook(() => useActivityFeed(3), { wrapper: wrap(client) });

    await waitFor(() => expect(result.current.failed).toBe(true));
    expect(result.current.entries).toEqual([]);
    expect(result.current.loading).toBe(false);
  });
});

describe('usePendingInvitations', () => {
  let client;

  beforeEach(() => {
    jest.clearAllMocks();
    console.error = jest.fn();
    client = createQueryClient();
    fetchBoardInvitations.mockResolvedValue([{ id: 1, email: 'a@b.test' }]);
  });

  it('reads the invitations of a board its reader owns', async () => {
    const { result } = renderHook(() => usePendingInvitations(5, true), { wrapper: wrap(client) });

    await waitFor(() => expect(result.current.invitations).toHaveLength(1));
    expect(fetchBoardInvitations).toHaveBeenCalledWith(5);
  });

  it('does not read them for a board the reader only belongs to', async () => {
    const { result } = renderHook(() => usePendingInvitations(5, false), { wrapper: wrap(client) });

    await act(async () => {});

    expect(fetchBoardInvitations).not.toHaveBeenCalled();
    expect(result.current.invitations).toEqual([]);
  });

  it('does not read them before there is a board', async () => {
    const { result } = renderHook(() => usePendingInvitations(undefined, true), { wrapper: wrap(client) });

    await act(async () => {});

    expect(fetchBoardInvitations).not.toHaveBeenCalled();
    expect(result.current.invitations).toEqual([]);
  });

  it('shows none once the reader no longer owns the board', async () => {
    const { result, rerender } = renderHook(({ owned }) => usePendingInvitations(5, owned), {
      wrapper: wrap(client),
      initialProps: { owned: true },
    });
    await waitFor(() => expect(result.current.invitations).toHaveLength(1));

    rerender({ owned: false });

    expect(result.current.invitations).toEqual([]);
  });

  it('reads again on reload and shows the new answer', async () => {
    const { result } = renderHook(() => usePendingInvitations(5, true), { wrapper: wrap(client) });
    await waitFor(() => expect(result.current.invitations).toHaveLength(1));

    fetchBoardInvitations.mockResolvedValue([]);
    await act(async () => {
      await result.current.reload();
    });

    await waitFor(() => expect(result.current.invitations).toEqual([]));
    expect(fetchBoardInvitations).toHaveBeenCalledTimes(2);
  });

  it('shows none when the read fails', async () => {
    fetchBoardInvitations.mockRejectedValue(new Error('down'));

    const { result } = renderHook(() => usePendingInvitations(5, true), { wrapper: wrap(client) });

    await waitFor(() => expect(console.error).toHaveBeenCalled());
    expect(result.current.invitations).toEqual([]);
  });
});
