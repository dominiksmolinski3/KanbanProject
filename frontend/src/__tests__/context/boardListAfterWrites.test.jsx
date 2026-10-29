import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { useBoards } from '../../context/kanban/useBoards';
import { boardsKeys } from '../../context/kanban/boardQueries';
import { createQueryClient } from '../../services/queryClient';
import * as api from '../../services/api';
import * as boardApi from '../../services/boardApi';

jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn(), info: jest.fn() },
}));

jest.mock('../../services/api', () => ({
  getActiveBoardId: jest.fn(),
  setActiveBoardId: jest.fn(),
}));

jest.mock('../../services/boardApi', () => ({
  fetchBoards: jest.fn(),
  fetchCurrentBoard: jest.fn(),
  fetchMyInvitations: jest.fn(),
  createBoard: jest.fn(),
  acceptInvitation: jest.fn(),
}));

const board = (id, name = `Board ${id}`) => ({ id, name, ownerId: 1, owned: true, members: [] });

describe('the lists read after a board write', () => {
  let client;
  const wrapper = ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;

  const mount = async () => {
    const { result } = renderHook(() => useBoards({ t: (key) => key }), { wrapper });
    await waitFor(() => expect(result.current.activeBoardId).toBe(1));
    return result;
  };

  const readStartedBeforeTheWrite = (key, fetcher, staleAnswer) => {
    fetcher.mockImplementationOnce(() => new Promise((resolve) => setTimeout(() => resolve(staleAnswer), 50)));
    act(() => {
      client.refetchQueries({ queryKey: key, exact: true });
    });
  };

  const waitOutTheStaleRead = () => act(() => new Promise((resolve) => setTimeout(resolve, 150)));

  beforeEach(() => {
    jest.clearAllMocks();
    console.error = jest.fn();
    client = createQueryClient();
    api.getActiveBoardId.mockReturnValue(null);
    boardApi.fetchBoards.mockResolvedValue([board(1)]);
    boardApi.fetchCurrentBoard.mockResolvedValue(board(1));
    boardApi.fetchMyInvitations.mockResolvedValue([{ id: 5, boardName: 'Shared' }]);
  });

  it('shows the board just joined, though a read of the list was already in flight', async () => {
    const result = await mount();
    readStartedBeforeTheWrite(boardsKeys.list, boardApi.fetchBoards, [board(1)]);

    boardApi.acceptInvitation.mockResolvedValue(board(2, 'Shared'));
    boardApi.fetchBoards.mockResolvedValue([board(1), board(2, 'Shared')]);
    boardApi.fetchMyInvitations.mockResolvedValue([]);
    await act(async () => {
      await result.current.acceptInvitation(5);
    });
    await waitOutTheStaleRead();

    expect(result.current.activeBoardId).toBe(2);
    expect(result.current.activeBoard?.name).toBe('Shared');
    expect(result.current.boards.map((b) => b.id)).toEqual([1, 2]);
  });

  it('drops the invitation just accepted, though a read of the invitations was already in flight', async () => {
    const result = await mount();
    await waitFor(() => expect(result.current.myInvitations).toHaveLength(1));
    readStartedBeforeTheWrite(
      boardsKeys.invitations, boardApi.fetchMyInvitations, [{ id: 5, boardName: 'Shared' }]);

    boardApi.acceptInvitation.mockResolvedValue(board(2, 'Shared'));
    boardApi.fetchBoards.mockResolvedValue([board(1), board(2, 'Shared')]);
    boardApi.fetchMyInvitations.mockResolvedValue([]);
    await act(async () => {
      await result.current.acceptInvitation(5);
    });
    await waitOutTheStaleRead();

    expect(result.current.myInvitations).toEqual([]);
  });

  it('shows a board just created, though a read of the list was already in flight', async () => {
    const result = await mount();
    readStartedBeforeTheWrite(boardsKeys.list, boardApi.fetchBoards, [board(1)]);

    boardApi.createBoard.mockResolvedValue(board(3, 'Roadmap'));
    boardApi.fetchBoards.mockResolvedValue([board(1), board(3, 'Roadmap')]);
    await act(async () => {
      await result.current.createBoard('Roadmap');
    });
    await waitOutTheStaleRead();

    expect(result.current.activeBoard?.name).toBe('Roadmap');
  });
});
