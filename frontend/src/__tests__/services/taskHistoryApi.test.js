import { getTaskColumnTimeSpentSummary } from '../../services/taskHistoryApi';

global.fetch = jest.fn();

beforeEach(() => {
  fetch.mockClear();
});

test('getTaskColumnTimeSpentSummary folds the history with one request and no board fetch', async () => {
  const base = new Date('2026-09-01T00:00:00Z').getTime();
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => [
      { columnId: 1, columnName: 'To Do', changedAt: new Date(base).toISOString() },
      { columnId: 2, columnName: 'Doing', changedAt: new Date(base + 2 * 60 * 60 * 1000).toISOString() },
      { columnId: 1, columnName: 'To Do', changedAt: new Date(base + 3 * 60 * 60 * 1000).toISOString() },
    ],
  });

  const summary = await getTaskColumnTimeSpentSummary(7);

  expect(fetch).toHaveBeenCalledTimes(1);
  expect(fetch).toHaveBeenCalledWith('/api/tasks/7/column-history');

  const doing = summary.find(c => c.columnId === 2);
  expect(doing).toEqual({ columnId: 2, columnName: 'Doing', totalTimeMs: 60 * 60 * 1000 });
  const todo = summary.find(c => c.columnId === 1);
  expect(todo.totalTimeMs).toBeGreaterThanOrEqual(2 * 60 * 60 * 1000);
});

test('getTaskColumnTimeSpentSummary returns [] for a task that never moved', async () => {
  fetch.mockResolvedValueOnce({ ok: true, json: async () => [] });

  await expect(getTaskColumnTimeSpentSummary(7)).resolves.toEqual([]);
  expect(fetch).toHaveBeenCalledTimes(1);
});
