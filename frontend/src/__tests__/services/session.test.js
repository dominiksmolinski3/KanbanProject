import {
  REFRESH_TOKEN_KEY,
  SESSION_ID_KEY,
  TOKEN_EXPIRY_KEY,
  TOKEN_KEY,
  clearSession,
  endSession,
  isAccessTokenExpired,
  refreshSession,
  getSessionId,
  resetRefreshState,
  storeSession
} from '../../services/session';
import { authService } from '../../services/authService';
import { ApiError } from '../../services/apiError';

jest.mock('../../services/authService', () => ({
  authService: {
    refresh: jest.fn(),
    logout: jest.fn()
  }
}));

describe('session', () => {
  const session = { token: 'access', expiresIn: 900_000, refreshToken: 'rotated' };

  beforeEach(() => {
    localStorage.clear();
    resetRefreshState();
    jest.clearAllMocks();
  });

  test('stores both tokens and an absolute expiry the server-sent milliseconds out', () => {
    const before = Date.now();
    storeSession({ token: 'a', expiresIn: 900_000, refreshToken: 'r' });

    expect(localStorage.getItem(TOKEN_KEY)).toBe('a');
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('r');
    const stored = Number(localStorage.getItem(TOKEN_EXPIRY_KEY));
    expect(stored).toBeGreaterThanOrEqual(before + 900_000);
    expect(stored).toBeLessThanOrEqual(Date.now() + 900_000);
  });

  test('remembers which session row this browser is, so the device list can say "this device"', () => {
    storeSession({ token: 'a', expiresIn: 900_000, refreshToken: 'r', sessionId: 12 });

    expect(getSessionId()).toBe('12');
    expect(localStorage.getItem(SESSION_ID_KEY)).toBe('12');
  });

  test('a rotation replaces the stored session id, because rotation writes a new row', () => {
    storeSession({ token: 'a', expiresIn: 900_000, refreshToken: 'r', sessionId: 12 });
    storeSession({ token: 'b', expiresIn: 900_000, refreshToken: 'r2', sessionId: 13 });

    expect(getSessionId()).toBe('13');
  });

  test('a response with no session id leaves the stored one alone rather than blanking it', () => {
    localStorage.setItem(SESSION_ID_KEY, '12');

    storeSession({ token: 'a', expiresIn: 900_000, refreshToken: 'r' });

    expect(getSessionId()).toBe('12');
  });

  test('clearing a session forgets the id with everything else', () => {
    storeSession({ token: 'a', expiresIn: 900_000, refreshToken: 'r', sessionId: 12 });

    clearSession();

    expect(getSessionId()).toBeNull();
  });

  test('a response with no refresh token still signs in and keeps the one already held', () => {
    localStorage.setItem(REFRESH_TOKEN_KEY, 'existing');

    storeSession({ token: 'a', expiresIn: 900_000 });

    expect(localStorage.getItem(TOKEN_KEY)).toBe('a');
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('existing');
  });

  test('clearing removes all three keys, not just the access token', () => {
    storeSession(session);

    clearSession();

    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(TOKEN_EXPIRY_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  });

  test('a token inside the skew window counts as expired already', () => {
    localStorage.setItem(TOKEN_EXPIRY_KEY, String(Date.now() + 3_000));

    expect(isAccessTokenExpired()).toBe(true);
  });

  test('a token with a minute left is not expired', () => {
    localStorage.setItem(TOKEN_EXPIRY_KEY, String(Date.now() + 60_000));

    expect(isAccessTokenExpired()).toBe(false);
  });

  test('renewing stores the new pair and answers with the new access token', async () => {
    localStorage.setItem(REFRESH_TOKEN_KEY, 'held');
    authService.refresh.mockResolvedValue(session);

    await expect(refreshSession()).resolves.toBe('access');

    expect(authService.refresh).toHaveBeenCalledWith('held');
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('rotated');
  });

  test('ten simultaneous callers make one refresh call between them', async () => {
    localStorage.setItem(REFRESH_TOKEN_KEY, 'held');
    authService.refresh.mockResolvedValue(session);

    const results = await Promise.all(Array.from({ length: 10 }, () => refreshSession()));

    expect(authService.refresh).toHaveBeenCalledTimes(1);
    expect(results).toEqual(Array(10).fill('access'));
  });

  test('a later renewal starts a new call rather than reusing the settled one', async () => {
    localStorage.setItem(REFRESH_TOKEN_KEY, 'held');
    authService.refresh.mockResolvedValue(session);

    await refreshSession();
    await refreshSession();

    expect(authService.refresh).toHaveBeenCalledTimes(2);
  });

  test('a rejected renewal clears the session and rejects every waiting caller', async () => {
    localStorage.setItem(TOKEN_KEY, 'stale');
    localStorage.setItem(REFRESH_TOKEN_KEY, 'withdrawn');
    authService.refresh.mockRejectedValue(new ApiError('Invalid refresh token', { status: 401 }));

    const [first, second] = await Promise.allSettled([refreshSession(), refreshSession()]);

    expect(first.status).toBe('rejected');
    expect(second.status).toBe('rejected');
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  });

  test.each([
    ['a network failure', new TypeError('Failed to fetch')],
    ['a server error', new ApiError('Unavailable', { status: 503 })],
  ])('%s keeps the session so the next call can renew', async (_, failure) => {
    localStorage.setItem(TOKEN_KEY, 'stale');
    localStorage.setItem(REFRESH_TOKEN_KEY, 'still-valid');
    authService.refresh.mockRejectedValue(failure);

    await expect(refreshSession()).rejects.toBe(failure);

    expect(localStorage.getItem(TOKEN_KEY)).toBe('stale');
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('still-valid');
  });

  test('with no refresh token there is nothing to renew and no call is made', async () => {
    await expect(refreshSession()).resolves.toBeNull();

    expect(authService.refresh).not.toHaveBeenCalled();
  });

  test('ending a session withdraws it on the server and clears it here', async () => {
    storeSession(session);
    authService.logout.mockResolvedValue(undefined);

    await endSession();

    expect(authService.logout).toHaveBeenCalledWith('rotated');
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
  });

  test('a server that cannot be reached still signs the person out here', async () => {
    storeSession(session);
    authService.logout.mockRejectedValue(new Error('Network error'));
    jest.spyOn(console, 'warn').mockImplementation(() => {});

    await expect(endSession()).resolves.toBeUndefined();

    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
  });

  describe('across tabs', () => {
    let otherTab;

    const queuedLocks = () => {
      let tail = Promise.resolve();
      return {
        request: jest.fn((name, callback) => {
          const granted = tail.then(() => callback({ name }));
          tail = granted.catch(() => {});
          return granted;
        }),
      };
    };

    const deferred = () => {
      let resolve;
      const promise = new Promise((res) => {
        resolve = res;
      });
      return { promise, resolve };
    };

    beforeEach(() => {
      Object.defineProperty(navigator, 'locks', { value: queuedLocks(), configurable: true });
      jest.isolateModules(() => {
        otherTab = require('../../services/session');
      });
    });

    afterEach(() => {
      delete navigator.locks;
    });

    test('a renewal holds the lock every tab of this origin shares', async () => {
      localStorage.setItem(REFRESH_TOKEN_KEY, 'held');
      authService.refresh.mockResolvedValue(session);

      await refreshSession();

      expect(navigator.locks.request).toHaveBeenCalledWith(otherTab.REFRESH_LOCK, expect.any(Function));
    });

    test('two tabs renewing together make one call, and the second uses what the first stored', async () => {
      localStorage.setItem(REFRESH_TOKEN_KEY, 'held');
      const firstCall = deferred();
      authService.refresh
        .mockReturnValueOnce(firstCall.promise)
        .mockResolvedValue({ token: 'forked', expiresIn: 900_000, refreshToken: 'forked' });

      const thisTab = refreshSession();
      const thatTab = otherTab.refreshSession();
      firstCall.resolve(session);

      await expect(thisTab).resolves.toBe('access');
      await expect(thatTab).resolves.toBe('access');
      expect(authService.refresh).toHaveBeenCalledTimes(1);
      expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBe('rotated');
    });

    test('a tab waiting behind a refused renewal ends signed out, without presenting the spent token', async () => {
      localStorage.setItem(TOKEN_KEY, 'stale');
      localStorage.setItem(REFRESH_TOKEN_KEY, 'withdrawn');
      authService.refresh.mockRejectedValue(new ApiError('Invalid refresh token', { status: 401 }));

      const thisTab = refreshSession();
      const thatTab = otherTab.refreshSession();

      await expect(thisTab).rejects.toBeInstanceOf(ApiError);
      await expect(thatTab).resolves.toBeNull();
      expect(authService.refresh).toHaveBeenCalledTimes(1);
    });

    test('a tab that renews after the other has finished presents the rotated token', async () => {
      localStorage.setItem(REFRESH_TOKEN_KEY, 'held');
      authService.refresh
        .mockResolvedValueOnce(session)
        .mockResolvedValueOnce({ token: 'later', expiresIn: 900_000, refreshToken: 'later' });

      await refreshSession();
      await expect(otherTab.refreshSession()).resolves.toBe('later');

      expect(authService.refresh).toHaveBeenLastCalledWith('rotated');
    });
  });
});
