import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, waitFor } from '@testing-library/react';
import { toast } from 'react-toastify';
import { useBoards } from '../../context/kanban/useBoards';
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
  renameBoard: jest.fn(),
  deleteBoard: jest.fn(),
  inviteToBoard: jest.fn(),
  revokeBoardInvitation: jest.fn(),
  acceptInvitation: jest.fn(),
  declineInvitation: jest.fn(),
  removeBoardMember: jest.fn(),
}));

const board = (id, overrides = {}) => ({ id, name: `Board ${id}`, ownerId: 1, owned: true, members: [], ...overrides });
const t = (key, values) => (values ? `${key} ${JSON.stringify(values)}` : key);

describe('useBoards', () => {
  let client;
  const wrapper = ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;

  const mount = async () => {
    const hook = renderHook(() => useBoards({ t }), { wrapper });
    await waitFor(() => expect(hook.result.current.activeBoardId).toBe(1));
    return hook.result;
  };

  beforeEach(() => {
    jest.clearAllMocks();
    console.error = jest.fn();
    client = createQueryClient();
    api.getActiveBoardId.mockReturnValue(null);
    boardApi.fetchBoards.mockResolvedValue([board(1), board(2)]);
    boardApi.fetchCurrentBoard.mockResolvedValue(board(1));
    boardApi.fetchMyInvitations.mockResolvedValue([]);
  });

  describe('creating a board', () => {
    it('opens the new board once the list holds it', async () => {
      const result = await mount();
      boardApi.createBoard.mockResolvedValue(board(3, { name: 'Roadmap' }));
      boardApi.fetchBoards.mockResolvedValue([board(1), board(2), board(3, { name: 'Roadmap' })]);

      let created;
      await act(async () => {
        created = await result.current.createBoard('Roadmap');
      });

      expect(boardApi.createBoard).toHaveBeenCalledWith('Roadmap');
      expect(created).toEqual(board(3, { name: 'Roadmap' }));
      expect(result.current.activeBoardId).toBe(3);
      expect(result.current.activeBoard.name).toBe('Roadmap');
      expect(api.setActiveBoardId).toHaveBeenLastCalledWith(3);
      expect(toast.success).toHaveBeenCalledWith('notifications.boardCreated');
    });

    it('stays on the open board and reports a refusal', async () => {
      const result = await mount();
      boardApi.createBoard.mockRejectedValue(new Error('too many boards'));

      let created;
      await act(async () => {
        created = await result.current.createBoard('Roadmap');
      });

      expect(created).toBeNull();
      expect(result.current.activeBoardId).toBe(1);
      expect(toast.success).not.toHaveBeenCalled();
      expect(toast.error).toHaveBeenCalledWith('notifications.errorOccurred {"message":"too many boards"}');
    });
  });

  describe('renaming a board', () => {
    it('shows the new name from the list it reads again', async () => {
      const result = await mount();
      boardApi.renameBoard.mockResolvedValue(board(1, { name: 'Renamed' }));
      boardApi.fetchBoards.mockResolvedValue([board(1, { name: 'Renamed' }), board(2)]);

      let renamed;
      await act(async () => {
        renamed = await result.current.renameBoard(1, 'Renamed');
      });

      expect(renamed).toBe(true);
      expect(boardApi.renameBoard).toHaveBeenCalledWith(1, 'Renamed');
      expect(result.current.activeBoard.name).toBe('Renamed');
      expect(toast.success).toHaveBeenCalledWith('notifications.boardRenamed');
    });

    it('keeps the old name and reports a refusal', async () => {
      const result = await mount();
      boardApi.renameBoard.mockRejectedValue(new Error('not the owner'));

      let renamed;
      await act(async () => {
        renamed = await result.current.renameBoard(1, 'Renamed');
      });

      expect(renamed).toBe(false);
      expect(result.current.activeBoard.name).toBe('Board 1');
      expect(toast.error).toHaveBeenCalledWith('notifications.errorOccurred {"message":"not the owner"}');
    });
  });

  it('a delete the server refuses leaves the reader where they were', async () => {
    const result = await mount();
    boardApi.deleteBoard.mockRejectedValue(new Error('not the owner'));

    let deleted;
    await act(async () => {
      deleted = await result.current.deleteBoard(1);
    });

    expect(deleted).toBe(false);
    expect(result.current.activeBoardId).toBe(1);
    expect(toast.error).toHaveBeenCalledWith('notifications.errorOccurred {"message":"not the owner"}');
  });

  describe('invitations sent', () => {
    it('hands back the invitation it sent, with the role asked for', async () => {
      const result = await mount();
      const invitation = { id: 9, email: 'ada@example.com', role: 'VIEWER' };
      boardApi.inviteToBoard.mockResolvedValue(invitation);

      let sent;
      await act(async () => {
        sent = await result.current.inviteToBoard(1, 'ada@example.com', 'VIEWER');
      });

      expect(sent).toEqual(invitation);
      expect(boardApi.inviteToBoard).toHaveBeenCalledWith(1, 'ada@example.com', 'VIEWER');
      expect(toast.info).toHaveBeenCalledWith('notifications.boardInvitationSent');
    });

    it('hands back nothing for an invitation the server refuses', async () => {
      const result = await mount();
      boardApi.inviteToBoard.mockRejectedValue(new Error('already a member'));

      let sent;
      await act(async () => {
        sent = await result.current.inviteToBoard(1, 'ada@example.com');
      });

      expect(sent).toBeNull();
      expect(toast.info).not.toHaveBeenCalled();
      expect(toast.error).toHaveBeenCalledWith('notifications.errorOccurred {"message":"already a member"}');
    });

    it('revokes one, and says whether it could', async () => {
      const result = await mount();
      boardApi.revokeBoardInvitation.mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('gone'));

      let first;
      let second;
      await act(async () => {
        first = await result.current.revokeInvitation(1, 9);
        second = await result.current.revokeInvitation(1, 9);
      });

      expect(boardApi.revokeBoardInvitation).toHaveBeenCalledWith(1, 9);
      expect(first).toBe(true);
      expect(second).toBe(false);
      expect(toast.success).toHaveBeenCalledTimes(1);
      expect(toast.success).toHaveBeenCalledWith('notifications.boardInvitationRevoked');
      expect(toast.error).toHaveBeenCalledWith('notifications.errorOccurred {"message":"gone"}');
    });
  });

  describe('invitations received', () => {
    it('accepting one opens the board it leads to and drops it from the list', async () => {
      boardApi.fetchMyInvitations.mockResolvedValue([{ id: 5, boardName: 'Shared' }]);
      const result = await mount();
      await waitFor(() => expect(result.current.myInvitations).toHaveLength(1));

      boardApi.acceptInvitation.mockResolvedValue(board(8, { name: 'Shared', owned: false }));
      boardApi.fetchBoards.mockResolvedValue([board(1), board(2), board(8, { name: 'Shared', owned: false })]);
      boardApi.fetchMyInvitations.mockResolvedValue([]);
      let accepted;
      await act(async () => {
        accepted = await result.current.acceptInvitation(5);
      });

      expect(boardApi.acceptInvitation).toHaveBeenCalledWith(5);
      expect(accepted.id).toBe(8);
      expect(result.current.activeBoardId).toBe(8);
      expect(result.current.activeBoard.name).toBe('Shared');
      expect(result.current.myInvitations).toEqual([]);
      expect(toast.success).toHaveBeenCalledWith('notifications.boardInvitationAccepted {"name":"Shared"}');
    });

    it('an acceptance the server refuses leaves the reader where they were', async () => {
      const result = await mount();
      boardApi.acceptInvitation.mockRejectedValue(new Error('expired'));

      let accepted;
      await act(async () => {
        accepted = await result.current.acceptInvitation(5);
      });

      expect(accepted).toBeNull();
      expect(result.current.activeBoardId).toBe(1);
      expect(toast.error).toHaveBeenCalledWith('notifications.errorOccurred {"message":"expired"}');
    });

    it('declining one drops it from the list and keeps the board open', async () => {
      boardApi.fetchMyInvitations.mockResolvedValue([{ id: 5, boardName: 'Shared' }]);
      const result = await mount();
      await waitFor(() => expect(result.current.myInvitations).toHaveLength(1));

      boardApi.declineInvitation.mockResolvedValue(undefined);
      boardApi.fetchMyInvitations.mockResolvedValue([]);
      let declined;
      await act(async () => {
        declined = await result.current.declineInvitation(5);
      });

      expect(declined).toBe(true);
      expect(boardApi.declineInvitation).toHaveBeenCalledWith(5);
      expect(result.current.myInvitations).toEqual([]);
      expect(result.current.activeBoardId).toBe(1);
      expect(toast.info).toHaveBeenCalledWith('notifications.boardInvitationDeclined');
    });

    it('a decline the server refuses keeps the invitation', async () => {
      boardApi.fetchMyInvitations.mockResolvedValue([{ id: 5, boardName: 'Shared' }]);
      const result = await mount();
      await waitFor(() => expect(result.current.myInvitations).toHaveLength(1));
      boardApi.declineInvitation.mockRejectedValue(new Error('down'));

      let declined;
      await act(async () => {
        declined = await result.current.declineInvitation(5);
      });

      expect(declined).toBe(false);
      expect(result.current.myInvitations).toHaveLength(1);
      expect(toast.error).toHaveBeenCalledWith('notifications.errorOccurred {"message":"down"}');
    });
  });

  describe('removing a member', () => {
    it('keeps the board open when someone else leaves it', async () => {
      const result = await mount();
      const updated = board(1, { members: [] });
      boardApi.removeBoardMember.mockResolvedValue(updated);

      let answer;
      await act(async () => {
        answer = await result.current.removeBoardMember(1, 4);
      });

      expect(answer).toEqual(updated);
      expect(boardApi.removeBoardMember).toHaveBeenCalledWith(1, 4);
      expect(result.current.activeBoardId).toBe(1);
      expect(toast.success).toHaveBeenCalledWith('notifications.boardMemberRemoved');
    });

    it('moves the reader to another board when they removed themselves from the open one', async () => {
      const result = await mount();
      boardApi.removeBoardMember.mockResolvedValue(null);
      boardApi.fetchBoards.mockResolvedValue([board(2)]);

      await act(async () => {
        await result.current.removeBoardMember(1, 1);
      });

      expect(result.current.activeBoardId).toBe(2);
      expect(api.setActiveBoardId).toHaveBeenLastCalledWith(2);
    });

    it('stays put when a board that is not open loses a member', async () => {
      const result = await mount();
      boardApi.removeBoardMember.mockResolvedValue(null);
      boardApi.fetchBoards.mockResolvedValue([board(3), board(1)]);

      await act(async () => {
        await result.current.removeBoardMember(2, 1);
      });

      expect(result.current.activeBoardId).toBe(1);
    });

    it('hands back nothing for a removal the server refuses', async () => {
      const result = await mount();
      boardApi.removeBoardMember.mockRejectedValue(new Error('not the owner'));

      let answer;
      await act(async () => {
        answer = await result.current.removeBoardMember(1, 4);
      });

      expect(answer).toBeNull();
      expect(result.current.activeBoardId).toBe(1);
      expect(toast.error).toHaveBeenCalledWith('notifications.errorOccurred {"message":"not the owner"}');
    });
  });

  describe('a notice that the boards changed', () => {
    const notice = async (result) => {
      let answer;
      await act(async () => {
        answer = await result.current.handleBoardsChanged();
      });
      return answer;
    };

    it('says the open board changed when its entry did', async () => {
      const result = await mount();
      boardApi.fetchBoards.mockResolvedValue([board(1, { members: [{ id: 4 }] }), board(2)]);

      expect(await notice(result)).toBe(true);
      expect(result.current.activeBoardId).toBe(1);
    });

    it('says nothing changed when the open board reads the same', async () => {
      const result = await mount();

      expect(await notice(result)).toBe(false);
    });

    it('moves the reader off a board they lost, and tells them', async () => {
      const result = await mount();
      boardApi.fetchBoards.mockResolvedValue([board(2)]);

      expect(await notice(result)).toBe(false);
      expect(result.current.activeBoardId).toBe(2);
      expect(toast.info).toHaveBeenCalledWith('notifications.boardNoLongerAvailable');
    });

    it('reads the invitations again', async () => {
      const result = await mount();
      boardApi.fetchMyInvitations.mockResolvedValue([{ id: 6, boardName: 'New' }]);

      await notice(result);

      await waitFor(() => expect(result.current.myInvitations).toEqual([{ id: 6, boardName: 'New' }]));
    });

    it('keeps the open board when the list cannot be read', async () => {
      const result = await mount();
      boardApi.fetchBoards.mockRejectedValue(new Error('down'));

      expect(await notice(result)).toBe(false);
      expect(result.current.activeBoardId).toBe(1);
      expect(toast.info).not.toHaveBeenCalled();
    });

    it('has nothing to compare before a board is open', async () => {
      boardApi.fetchCurrentBoard.mockReturnValue(new Promise(() => {}));
      const { result } = renderHook(() => useBoards({ t }), { wrapper });
      await waitFor(() => expect(result.current.boards).toHaveLength(2));

      expect(await notice(result)).toBe(false);
      expect(result.current.activeBoardId).toBeNull();
    });
  });

  it('selecting the board that is already open writes nothing', async () => {
    const result = await mount();
    api.setActiveBoardId.mockClear();

    act(() => result.current.selectBoard(1));

    expect(api.setActiveBoardId).not.toHaveBeenCalled();
    expect(result.current.activeBoardId).toBe(1);
  });
});
