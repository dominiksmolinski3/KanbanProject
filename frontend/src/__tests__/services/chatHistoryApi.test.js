import { fetchBoardChatHistory, fetchDirectChatHistory, DEFAULT_PAGE_SIZE } from '../../services/chatHistoryApi';

describe('chatHistoryApi', () => {
  beforeEach(() => {
    global.fetch = jest.fn();
  });

  const respondWith = (body) =>
    fetch.mockResolvedValueOnce({ ok: true, status: 200, json: async () => body });

  const queryOf = () => new URL(fetch.mock.calls[0][0], 'http://localhost').searchParams;
  const pathOf = () => new URL(fetch.mock.calls[0][0], 'http://localhost').pathname;

  describe('fetchBoardChatHistory', () => {
    test('sends the board, the page and the size against /api/chat', async () => {
      respondWith({ messages: [], page: 1, totalPages: 3 });

      await fetchBoardChatHistory({ boardId: 7, page: 1 });

      expect(pathOf()).toBe('/api/chat');
      const params = queryOf();
      expect(params.get('boardId')).toBe('7');
      expect(params.get('page')).toBe('1');
      expect(params.get('size')).toBe(String(DEFAULT_PAGE_SIZE));
    });

    test('a missing board id is left out rather than sent as the word undefined', async () => {
      respondWith({ messages: [], page: 0, totalPages: 0 });

      await fetchBoardChatHistory();

      expect(queryOf().has('boardId')).toBe(false);
    });

    test('a refused request throws rather than resolving to nothing', async () => {
      fetch.mockResolvedValueOnce({ ok: false, status: 404 });

      await expect(fetchBoardChatHistory({ boardId: 7 })).rejects.toThrow('404');
    });
  });

  describe('fetchDirectChatHistory', () => {
    test('sends the peer as "with" against /api/chat/direct', async () => {
      respondWith({ messages: [], page: 0, totalPages: 1 });

      await fetchDirectChatHistory({ peer: 'bob@example.com', page: 2, size: 10 });

      expect(pathOf()).toBe('/api/chat/direct');
      const params = queryOf();
      expect(params.get('with')).toBe('bob@example.com');
      expect(params.get('page')).toBe('2');
      expect(params.get('size')).toBe('10');
    });

    test('a peer sharing no board throws on the 404', async () => {
      fetch.mockResolvedValueOnce({ ok: false, status: 404 });

      await expect(fetchDirectChatHistory({ peer: 'nobody@example.com' })).rejects.toThrow('404');
    });
  });
});
