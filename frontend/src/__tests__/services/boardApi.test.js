import * as boardApi from '../../services/boardApi';
import * as api from '../../services/api';

global.fetch = jest.fn();

const board = {
  id: 3,
  name: 'Kanban',
  ownerId: 1,
  owned: true,
  members: [{ id: 1, email: 'owner@example.com', name: 'Owner', wipLimit: null }]
};

const respondWith = (body, ok = true, status = 200) => {
  fetch.mockResolvedValueOnce({ ok, status, json: async () => body });
};

describe('boardApi', () => {
  beforeEach(() => {
    fetch.mockClear();
    localStorage.clear();
  });

  test('lists the boards the caller can see', async () => {
    respondWith([board]);

    await expect(boardApi.fetchBoards()).resolves.toEqual([board]);
    expect(fetch).toHaveBeenCalledWith('/api/boards');
  });

  test('asks the server which board is the caller’s own', async () => {
    respondWith(board);

    await expect(boardApi.fetchCurrentBoard()).resolves.toEqual(board);
    expect(fetch).toHaveBeenCalledWith('/api/boards/current');
  });

  test('invites by email address, and the answer is the invitation rather than the board', async () => {
    respondWith({ id: 7, boardId: 3, email: 'colleague@example.com', status: 'PENDING' });

    const invitation = await boardApi.inviteToBoard(3, 'colleague@example.com');

    const [url, options] = fetch.mock.calls[0];
    expect(url).toBe('/api/boards/3/invitations');
    expect(options.method).toBe('POST');
    expect(JSON.parse(options.body)).toEqual({ email: 'colleague@example.com' });
    // No member list comes back, which is the whole point: the old route answered with one, so an
    // owner could diff it and learn whether that address had an account here.
    expect(invitation.members).toBeUndefined();
  });

  test('the invitee reads and answers their own invitations, off the board path', async () => {
    respondWith([{ id: 7, boardName: 'Kanban' }]);
    await boardApi.fetchMyInvitations();
    expect(fetch).toHaveBeenCalledWith('/api/invitations');

    respondWith(board);
    await boardApi.acceptInvitation(7);
    expect(fetch).toHaveBeenLastCalledWith('/api/invitations/7/accept', { method: 'POST' });

    fetch.mockResolvedValueOnce({ ok: true, status: 204 });
    await boardApi.declineInvitation(7);
    expect(fetch).toHaveBeenLastCalledWith('/api/invitations/7/decline', { method: 'POST' });
  });

  test('the owner lists and revokes what is outstanding on their board', async () => {
    respondWith([{ id: 7, email: 'waiting@example.com' }]);
    await boardApi.fetchBoardInvitations(3);
    expect(fetch).toHaveBeenCalledWith('/api/boards/3/invitations');

    fetch.mockResolvedValueOnce({ ok: true, status: 204 });
    await boardApi.revokeBoardInvitation(3, 7);
    expect(fetch).toHaveBeenLastCalledWith('/api/boards/3/invitations/7', { method: 'DELETE' });
  });

  test('removing a member answers with the board that is left', async () => {
    respondWith({ ...board, members: [] });

    const result = await boardApi.removeBoardMember(3, 9);

    expect(fetch).toHaveBeenCalledWith('/api/boards/3/members/9', { method: 'DELETE' });
    expect(result.members).toEqual([]);
  });

  test('a refused request throws rather than resolving to nothing', async () => {
    fetch.mockResolvedValueOnce({ ok: false, status: 403 });

    await expect(boardApi.renameBoard(3, 'Nope')).rejects.toThrow('403');
  });
});

describe('the active board', () => {
  beforeEach(() => {
    fetch.mockClear();
    localStorage.clear();
  });

  test('is left out entirely until one is chosen, which means "whichever board is mine"', async () => {
    fetch.mockResolvedValueOnce({ ok: true, json: async () => [] });

    await api.fetchColumns();

    expect(fetch).toHaveBeenCalledWith('/api/columns');
  });

  test('scopes the listings once a board is chosen', async () => {
    api.setActiveBoardId(7);

    fetch.mockResolvedValueOnce({ ok: true, json: async () => [] });
    await api.fetchColumns();
    fetch.mockResolvedValueOnce({ ok: true, json: async () => [] });
    await api.fetchTasks();
    fetch.mockResolvedValueOnce({ ok: true, json: async () => [] });
    await api.fetchRows();

    expect(fetch).toHaveBeenNthCalledWith(1, '/api/columns?boardId=7');
    expect(fetch).toHaveBeenNthCalledWith(2, '/api/tasks?boardId=7');
    expect(fetch).toHaveBeenNthCalledWith(3, '/api/rows?boardId=7');
  });

  test('survives a reload, because a board is a place the user chose to be', () => {
    api.setActiveBoardId(12);

    expect(api.getActiveBoardId()).toBe(12);
    expect(localStorage.getItem('activeBoardId')).toBe('12');

    api.setActiveBoardId(null);
    expect(api.getActiveBoardId()).toBeNull();
  });

  test('a create lands on the chosen board too, not only the listings', async () => {
    api.setActiveBoardId(4);
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ id: 1 }) });

    await api.addColumn('Doing', 3);

    expect(fetch.mock.calls[0][0]).toBe('/api/columns?boardId=4');
  });
});
