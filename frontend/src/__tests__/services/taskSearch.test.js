import * as api from '../../services/api';

/**
 * The query string this builds, which is the whole of what the client contributes to a search.
 *
 * Two things here are easy to get wrong in a way no test above this level would catch. Collections
 * have to be *repeated* parameters — `?label=bug&label=ux` — because that is what binding to a
 * `Set<String>` on the server expects; a comma-joined value arrives as one label literally named
 * "bug,ux" and quietly matches nothing. And an unset filter has to be *absent* rather than sent
 * empty, because the server reads an absent parameter as "not a filter" and an empty one as a
 * filter that matches nothing.
 */
describe('searching tasks', () => {
  beforeEach(() => {
    global.fetch = jest.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ tasks: [], page: 0, size: 25, totalTasks: 0, totalPages: 0 })
    });
    localStorage.clear();
  });

  const queryOf = () => new URL(`http://x${fetch.mock.calls[0][0]}`).searchParams;

  test('an empty search sends only the paging, so the server sees no filters at all', async () => {
    await api.searchTasks();

    const query = queryOf();
    expect(query.get('q')).toBeNull();
    expect(query.getAll('label')).toEqual([]);
    expect(query.getAll('assignee')).toEqual([]);
    expect(query.get('completed')).toBeNull();
    expect(query.get('page')).toBe('0');
    expect(query.get('size')).toBe(String(api.SEARCH_PAGE_SIZE));
  });

  test('a blank term is not sent, because the server would read it as a filter', async () => {
    await api.searchTasks({ q: '   ' });

    expect(queryOf().get('q')).toBeNull();
  });

  test('the term is trimmed', async () => {
    await api.searchTasks({ q: '  deploy ' });

    expect(queryOf().get('q')).toBe('deploy');
  });

  test('labels are repeated parameters, not one comma-joined value', async () => {
    await api.searchTasks({ labels: ['bug', 'ux'] });

    expect(queryOf().getAll('label')).toEqual(['bug', 'ux']);
  });

  test('assignees are repeated too', async () => {
    await api.searchTasks({ assignees: [3, 7] });

    expect(queryOf().getAll('assignee')).toEqual(['3', '7']);
  });

  test('completed false is sent, because false is a filter and null is not', async () => {
    await api.searchTasks({ completed: false });

    expect(queryOf().get('completed')).toBe('false');
  });

  test('the page and size asked for are the ones sent', async () => {
    await api.searchTasks({ page: 3, size: 10 });

    const query = queryOf();
    expect(query.get('page')).toBe('3');
    expect(query.get('size')).toBe('10');
  });

  test('the active board rides along, the same way every other listing does', async () => {
    api.setActiveBoardId(12);

    await api.searchTasks({ q: 'ship' });

    expect(queryOf().get('boardId')).toBe('12');
  });

  test('a page size over the server ceiling is refused here, before the request', async () => {
    await expect(api.searchTasks({ size: api.MAX_SEARCH_PAGE_SIZE + 1 }))
      .rejects.toThrow(String(api.MAX_SEARCH_PAGE_SIZE));
    expect(fetch).not.toHaveBeenCalled();
  });

  test('a refused search is an error rather than an empty result set', async () => {
    fetch.mockResolvedValueOnce({ ok: false, status: 400 });

    await expect(api.searchTasks({ q: 'x' })).rejects.toThrow('400');
  });

  test('the results come back as the server sent them', async () => {
    const page = { tasks: [{ id: 1, title: 'ship it' }], page: 0, size: 25, totalTasks: 1, totalPages: 1 };
    fetch.mockResolvedValueOnce({ ok: true, json: async () => page });

    await expect(api.searchTasks({ q: 'ship' })).resolves.toEqual(page);
  });
});
