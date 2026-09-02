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

jest.mock('../../services/authService', () => ({
  authService: {
    refresh: jest.fn(),
    logout: jest.fn()
  }
}));

/**
 * The two properties that make refresh tokens safe to hold in a browser at all.
 *
 * A rotating token is worth exactly one use, so two concurrent renewals would present the same
 * token twice — which the server reads as theft and answers by withdrawing every session the
 * account has. A board load fires a dozen requests at once, so "concurrent" here is the normal
 * case, not the edge one.
 *
 * And a renewal that fails has to leave nothing behind: a stored token the server keeps rejecting
 * only delays the sign-in screen while every request in between fails on its own.
 */
describe('session', () => {
  // expiresIn is milliseconds — jwtService.getExpirationTime() passed straight through, the same
  // 900_000 every /auth test on the server asserts.
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
    // Fifteen minutes, not fifteen thousand: reading expiresIn as seconds put this ten days out
    // and left isAccessTokenExpired permanently false.
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

    // Three seconds is long enough to send a request and short enough that it can arrive after
    // the token has lapsed. Treating it as gone costs one early renewal.
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

    // The whole point: a second call would present a token the first has already spent, and the
    // server would read that as a stolen chain and sign the account out everywhere.
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
    authService.refresh.mockRejectedValue(new Error('Invalid email or password'));

    const [first, second] = await Promise.allSettled([refreshSession(), refreshSession()]);

    expect(first.status).toBe('rejected');
    expect(second.status).toBe('rejected');
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull();
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
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
});
