import * as api from '../../services/api';

describe('task comments', () => {
  beforeEach(() => {
    global.fetch = jest.fn();
  });

  test('reads one page of a thread, newest first by the server', async () => {
    const page = { comments: [{ id: 1, body: 'hi' }], page: 0, size: 25, totalEntries: 1, totalPages: 1 };
    fetch.mockResolvedValueOnce({ ok: true, json: async () => page });

    await expect(api.fetchTaskComments(7)).resolves.toEqual(page);
    expect(fetch).toHaveBeenCalledWith('/api/tasks/7/comments?page=0&size=25');
  });

  test('asks for the page and size it is given', async () => {
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({}) });

    await api.fetchTaskComments(7, { page: 2, size: 50 });

    expect(fetch).toHaveBeenCalledWith('/api/tasks/7/comments?page=2&size=50');
  });

  test('a thread that will not load is an error', async () => {
    fetch.mockResolvedValueOnce({ ok: false, status: 404 });

    await expect(api.fetchTaskComments(7)).rejects.toThrow('404');
  });

  test('posts the body as JSON', async () => {
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ id: 3, body: 'hello' }) });

    await expect(api.addTaskComment(7, 'hello')).resolves.toEqual({ id: 3, body: 'hello' });
    expect(fetch).toHaveBeenCalledWith('/api/tasks/7/comments', expect.objectContaining({
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ body: 'hello' })
    }));
  });

  test('a refused post is an error, so the draft is kept', async () => {
    fetch.mockResolvedValueOnce({ ok: false, status: 403 });

    await expect(api.addTaskComment(7, 'hello')).rejects.toThrow('403');
  });

  test('an edit patches the one comment', async () => {
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ id: 3, body: 'better' }) });

    await api.editTaskComment(7, 3, 'better');

    expect(fetch).toHaveBeenCalledWith('/api/tasks/7/comments/3', expect.objectContaining({
      method: 'PATCH',
      body: JSON.stringify({ body: 'better' })
    }));
  });

  test("a refused edit - somebody else's comment - is an error", async () => {
    fetch.mockResolvedValueOnce({ ok: false, status: 403 });

    await expect(api.editTaskComment(7, 3, 'x')).rejects.toThrow('403');
  });

  test('a delete succeeds, and so does deleting one that is already gone', async () => {
    fetch.mockResolvedValueOnce({ ok: true }).mockResolvedValueOnce({ ok: false, status: 404 });

    await expect(api.deleteTaskComment(7, 3)).resolves.toBe(true);
    await expect(api.deleteTaskComment(7, 3)).resolves.toBe(true);
    expect(fetch).toHaveBeenCalledWith('/api/tasks/7/comments/3', { method: 'DELETE' });
  });

  test('any other refused delete is an error', async () => {
    fetch.mockResolvedValueOnce({ ok: false, status: 403 });

    await expect(api.deleteTaskComment(7, 3)).rejects.toThrow('403');
  });
});
