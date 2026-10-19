import { queryClient } from '../../services/queryClient';
import { clearSession, storeSession } from '../../services/session';

jest.mock('../../services/authService', () => ({
  authService: { refresh: jest.fn(), logout: jest.fn() }
}));

describe('the query cache and the session', () => {
  beforeEach(() => {
    queryClient.clear();
  });

  test('ending a session forgets everything read for it', () => {
    queryClient.setQueryData(['flow', 3], { boardId: 3 });

    clearSession();

    expect(queryClient.getQueryData(['flow', 3])).toBeUndefined();
  });

  test('renewing a token keeps what was read, since it is the same person', () => {
    queryClient.setQueryData(['flow', 3], { boardId: 3 });

    storeSession({ token: 'jwt', expiresIn: 60_000 });

    expect(queryClient.getQueryData(['flow', 3])).toEqual({ boardId: 3 });
  });

  test('a request that fails is not retried behind the screen', () => {
    expect(queryClient.getDefaultOptions().queries.retry).toBe(false);
  });
});
