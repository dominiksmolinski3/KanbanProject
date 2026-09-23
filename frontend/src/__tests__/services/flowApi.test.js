import { defineFlow, fetchFlowMetrics } from '../../services/flowApi';

describe('fetchFlowMetrics', () => {
  beforeEach(() => {
    global.fetch = jest.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve({ boardId: 3 }) });
  });

  test('sends the board, the window and the chosen columns', async () => {
    await fetchFlowMetrics({ boardId: 3, from: '2026-09-01', to: '2026-09-30', start: 11, done: 12 });

    expect(global.fetch).toHaveBeenCalledWith(
      '/api/flow?boardId=3&from=2026-09-01&to=2026-09-30&start=11&done=12');
  });

  test('leaves out what was not chosen', async () => {
    await fetchFlowMetrics({ boardId: null, start: '', done: undefined });

    expect(global.fetch).toHaveBeenCalledWith('/api/flow?');
  });

  test('answers the server body', async () => {
    await expect(fetchFlowMetrics()).resolves.toEqual({ boardId: 3 });
  });

  test('throws on a refusal so the screen can say so', async () => {
    global.fetch.mockResolvedValue({ ok: false, status: 400 });

    await expect(fetchFlowMetrics({})).rejects.toThrow('400');
  });
});

describe('defineFlow', () => {
  beforeEach(() => {
    global.fetch = jest.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve({ boardId: 3 }) });
  });

  test('puts both ends, with a null for an end going back to the default', async () => {
    await defineFlow({ boardId: 3, start: null, done: 12 });

    expect(global.fetch).toHaveBeenCalledWith('/api/flow/definition?boardId=3', {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ startColumnId: null, doneColumnId: 12 })
    });
  });

  test("leaves the board out when there is none, which means the caller's own", async () => {
    await defineFlow({ done: 12 });

    expect(global.fetch.mock.calls[0][0]).toBe('/api/flow/definition?');
  });

  test('throws on a refusal so the screen can say so', async () => {
    global.fetch.mockResolvedValue({ ok: false, status: 403 });

    await expect(defineFlow({ boardId: 3 })).rejects.toThrow('403');
  });
});
