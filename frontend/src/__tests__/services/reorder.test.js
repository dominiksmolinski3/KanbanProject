import {
  ConcurrentModificationError,
  reorderColumns,
  reorderRows,
  reorderTasks
} from '../../services/api';

/**
 * One call per drag, and one specific answer for the one status this route can newly return.
 *
 * A 409 is not a failure in the usual sense: nothing is broken, nothing was half-applied, and the
 * only useful response is to reload. Giving it its own type is what lets the caller say that
 * instead of raising a generic error toast over a board that is simply out of date.
 */
describe('reorder helpers', () => {
  beforeEach(() => {
    window.fetch = jest.fn();
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  const ok = (body) => ({ ok: true, status: 200, json: async () => body });

  test('a task reorder is one PATCH carrying the whole order', async () => {
    window.fetch.mockResolvedValue(ok([{ id: 3, position: 0 }]));

    await reorderTasks([3, 1, 2]);

    expect(window.fetch).toHaveBeenCalledTimes(1);
    const [url, options] = window.fetch.mock.calls[0];
    expect(url).toBe('/api/tasks/positions');
    expect(options.method).toBe('PATCH');
    expect(JSON.parse(options.body)).toEqual({ orderedIds: [3, 1, 2] });
  });

  test('columns and swimlanes use their own routes', async () => {
    window.fetch.mockResolvedValue(ok([]));

    await reorderColumns([2, 1]);
    await reorderRows([5, 4]);

    expect(window.fetch.mock.calls[0][0]).toBe('/api/columns/positions');
    expect(window.fetch.mock.calls[1][0]).toBe('/api/rows/positions');
  });

  test('the server order comes back, so the caller need not guess the new positions', async () => {
    const applied = [{ id: 3, position: 0 }, { id: 1, position: 1 }];
    window.fetch.mockResolvedValue(ok(applied));

    await expect(reorderTasks([3, 1])).resolves.toEqual(applied);
  });

  test('a 409 is its own type, naming what was contended', async () => {
    window.fetch.mockResolvedValue({ ok: false, status: 409 });

    await expect(reorderColumns([1, 2])).rejects.toBeInstanceOf(ConcurrentModificationError);
    await expect(reorderColumns([1, 2])).rejects.toThrow('changed by someone else');
  });

  test('any other failure stays an ordinary error', async () => {
    window.fetch.mockResolvedValue({ ok: false, status: 400 });

    await expect(reorderTasks([1, 1])).rejects.toThrow('Error reordering tasks: 400');
    await expect(reorderTasks([1, 1])).rejects.not.toBeInstanceOf(ConcurrentModificationError);
  });
});
