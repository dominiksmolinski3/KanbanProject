import { fetchFlowMetrics } from '../../services/flowApi';

/**
 * The request the flow screen makes: only the parameters it was given, so that "no start column"
 * reaches the server as absent - which it reads as "measure from arrival on the board" - rather
 * than as an empty string it would refuse.
 */
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
