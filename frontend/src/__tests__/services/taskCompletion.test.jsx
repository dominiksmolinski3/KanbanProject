import {
  updateTaskCompletion,
  setTaskDailyFocus,
  fetchDailyFocusTasks,
  ParentTaskNotCompletedError
} from '../../services/api';

global.fetch = jest.fn();

describe('task completion', () => {
  beforeEach(() => {
    fetch.mockClear();
  });

  test('completing a task patches the route that enforces the parent rule', async () => {
    const completed = { id: 7, title: 'Ship it', completed: true };
    fetch.mockResolvedValueOnce({ ok: true, json: async () => completed });

    const result = await updateTaskCompletion(7, true);

    expect(fetch).toHaveBeenCalledWith('/api/tasks/7/complete/true', expect.objectContaining({
      method: 'PATCH'
    }));
    expect(result).toEqual(completed);
  });

  test('un-completing sends false rather than dropping the flag', async () => {
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ id: 7, completed: false }) });

    await updateTaskCompletion(7, false);

    expect(fetch).toHaveBeenCalledWith('/api/tasks/7/complete/false', expect.anything());
  });

  test('an open parent is reported as its own error, not a generic failure', async () => {
    fetch.mockResolvedValueOnce({
      ok: false,
      status: 400,
      json: async () => ({ code: 'PARENT_TASK_NOT_COMPLETED' })
    });

    await expect(updateTaskCompletion(7, true)).rejects.toBeInstanceOf(ParentTaskNotCompletedError);
  });

  test('any other failure stays a plain error', async () => {
    // The WIP-status route had the opposite bug: every failure was reported as the one
    // recoverable refusal, which sent people looking at the wrong thing.
    fetch.mockResolvedValueOnce({ ok: false, status: 404, json: async () => ({ code: 'TASK_NOT_FOUND' }) });

    const failure = updateTaskCompletion(7, true);
    await expect(failure).rejects.toThrow('404');
    await expect(failure).rejects.not.toBeInstanceOf(ParentTaskNotCompletedError);
  });

  test('a 400 without the code is not read as the parent rule', async () => {
    fetch.mockResolvedValueOnce({ ok: false, status: 400, json: async () => ({ code: 'VALIDATION_ERROR' }) });

    await expect(updateTaskCompletion(7, true)).rejects.not.toBeInstanceOf(ParentTaskNotCompletedError);
  });

  test('a body that is not JSON does not mask the status', async () => {
    fetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => { throw new SyntaxError('not json'); }
    });

    await expect(updateTaskCompletion(7, true)).rejects.toThrow('500');
  });
});

describe('daily focus', () => {
  beforeEach(() => {
    fetch.mockClear();
  });

  test('setting the flag patches the task route', async () => {
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ id: 3, dailyFocus: true }) });

    const result = await setTaskDailyFocus(3, true);

    expect(fetch).toHaveBeenCalledWith('/api/tasks/3/daily-focus/true', expect.objectContaining({
      method: 'PATCH'
    }));
    expect(result.dailyFocus).toBe(true);
  });

  test('clearing the flag sends false', async () => {
    fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ id: 3, dailyFocus: false }) });

    await setTaskDailyFocus(3, false);

    expect(fetch).toHaveBeenCalledWith('/api/tasks/3/daily-focus/false', expect.anything());
  });

  test('a failed update throws rather than resolving undefined', async () => {
    fetch.mockResolvedValueOnce({ ok: false, status: 404 });

    await expect(setTaskDailyFocus(3, true)).rejects.toThrow('404');
  });

  test('the list route is read from the collection, not per task', async () => {
    const focused = [{ id: 1, dailyFocus: true }];
    fetch.mockResolvedValueOnce({ ok: true, json: async () => focused });

    await expect(fetchDailyFocusTasks()).resolves.toEqual(focused);
    expect(fetch).toHaveBeenCalledWith('/api/tasks/daily-focus');
  });
});
