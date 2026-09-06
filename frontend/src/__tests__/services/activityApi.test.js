import { fetchActivity, DEFAULT_PAGE_SIZE } from '../../services/activityApi';

describe('activityApi', () => {
  beforeEach(() => {
    global.fetch = jest.fn();
  });

  const respondWith = (body) =>
    fetch.mockResolvedValueOnce({ ok: true, status: 200, json: async () => body });

  const queryOf = () => new URL(fetch.mock.calls[0][0], 'http://localhost').searchParams;

  test('sends the board, the page and the size', async () => {
    respondWith({ activities: [], page: 0, size: 25, totalEntries: 0, totalPages: 0 });

    await fetchActivity({ boardId: 3, page: 2 });

    const params = queryOf();
    expect(params.get('boardId')).toBe('3');
    expect(params.get('page')).toBe('2');
    expect(params.get('size')).toBe(String(DEFAULT_PAGE_SIZE));
  });

  /*
   * Leaving boardId out means "the caller's own board" on every listing in this application, so
   * sending boardId=undefined - which is what a naive template literal produces - would ask for a
   * board id that is the literal string "undefined".
   */
  test('a missing board id is left out rather than sent as the word undefined', async () => {
    respondWith({ activities: [], page: 0, size: 25, totalEntries: 0, totalPages: 0 });

    await fetchActivity();

    expect(queryOf().has('boardId')).toBe(false);
  });

  test('a refused request throws rather than resolving to nothing', async () => {
    fetch.mockResolvedValueOnce({ ok: false, status: 400 });

    await expect(fetchActivity({ boardId: 3 })).rejects.toThrow('400');
  });
});
