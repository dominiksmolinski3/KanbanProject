import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { useUsers, USERS_KEY } from '../../hooks/useUsers';
import { createQueryClient } from '../../services/queryClient';
import { fetchUsers } from '../../services/api';

jest.mock('../../services/api', () => ({ fetchUsers: jest.fn() }));

describe('useUsers', () => {
  let client;
  const wrapper = ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;

  beforeEach(() => {
    jest.clearAllMocks();
    client = createQueryClient();
    fetchUsers.mockResolvedValue([{ id: 1, name: 'Ada' }]);
  });

  it('reads the users', async () => {
    const { result } = renderHook(() => useUsers(), { wrapper });

    await waitFor(() => expect(result.current.data).toEqual([{ id: 1, name: 'Ada' }]));
  });

  it('reads an answer that is not a list as nobody', async () => {
    fetchUsers.mockResolvedValue(null);

    const { result } = renderHook(() => useUsers(), { wrapper });

    await waitFor(() => expect(result.current.data).toEqual([]));
  });

  it('reads nothing while it is switched off, and reads when it is switched on', async () => {
    const { result, rerender } = renderHook(({ on }) => useUsers(on), { wrapper, initialProps: { on: false } });

    await act(async () => {});
    expect(fetchUsers).not.toHaveBeenCalled();
    expect(result.current.data).toBeUndefined();

    rerender({ on: true });
    await waitFor(() => expect(result.current.data).toHaveLength(1));
    expect(fetchUsers).toHaveBeenCalledTimes(1);
  });

  it('is one read however many screens ask at once', async () => {
    const first = renderHook(() => useUsers(), { wrapper });
    const second = renderHook(() => useUsers(), { wrapper });

    await waitFor(() => expect(first.result.current.data).toHaveLength(1));
    await waitFor(() => expect(second.result.current.data).toHaveLength(1));

    expect(fetchUsers).toHaveBeenCalledTimes(1);
  });

  it('is kept under a key other screens can write to', async () => {
    const { result } = renderHook(() => useUsers(), { wrapper });
    await waitFor(() => expect(result.current.data).toHaveLength(1));

    await act(async () => {
      client.setQueryData(USERS_KEY, []);
    });

    expect(result.current.data).toEqual([]);
  });
});
