import { authService } from './authService';

/**
 * Where the two halves of a session live, and the one place that renews them.
 *
 * The access token is a signed claim the server cannot take back, so it is short-lived; the refresh
 * token is a row the server can withdraw, so it is what actually keeps someone signed in. Both were
 * previously spread across `AuthContext` and `apiInterceptor` as bare `localStorage` calls with the
 * key names written out by hand in each place — which was survivable while there was one key and
 * stops being so at three.
 */

export const TOKEN_KEY = 'token';
export const TOKEN_EXPIRY_KEY = 'tokenExpiration';
export const REFRESH_TOKEN_KEY = 'refreshToken';

/**
 * Renew this long before the access token actually lapses.
 *
 * A request that leaves with a token expiring in 200ms can still arrive after it has, and the
 * server is right to reject it. Treating the last few seconds as already gone costs one early
 * refresh and removes a race nobody can reproduce.
 */
const EXPIRY_SKEW_MS = 10_000;

export function storeSession({ token, expiresIn, refreshToken }) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(TOKEN_EXPIRY_KEY, String(Date.now() + expiresIn * 1000));
  if (refreshToken) {
    localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
  }
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXPIRY_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
}

export const getAccessToken = () => localStorage.getItem(TOKEN_KEY);
export const getRefreshToken = () => localStorage.getItem(REFRESH_TOKEN_KEY);

export function isAccessTokenExpired() {
  const expiration = parseInt(localStorage.getItem(TOKEN_EXPIRY_KEY), 10);
  return Boolean(expiration) && Date.now() > expiration - EXPIRY_SKEW_MS;
}

/**
 * One renewal at a time, however many callers ask for it.
 *
 * A board load fires a dozen requests at once, and if the access token has lapsed every one of them
 * would otherwise start its own refresh. That is not merely wasteful: refresh tokens rotate, so the
 * second call would present a token the first has already spent, which the server reads as replay
 * and answers by withdrawing every session the account has. Sharing the in-flight promise is what
 * keeps a normal page load from looking like a stolen token.
 */
let inFlight = null;

export function refreshSession() {
  const refreshToken = getRefreshToken();
  if (!refreshToken) {
    return Promise.resolve(null);
  }
  if (!inFlight) {
    inFlight = authService
      .refresh(refreshToken)
      .then((session) => {
        storeSession(session);
        return session.token;
      })
      .catch((error) => {
        // The chain is over: expired, withdrawn, or already spent. Whichever it was, holding on to
        // a token the server will keep rejecting only delays the sign-in screen.
        clearSession();
        throw error;
      })
      .finally(() => {
        inFlight = null;
      });
  }
  return inFlight;
}

/** Test seam: the module-level promise otherwise leaks between cases. */
export function resetRefreshState() {
  inFlight = null;
}

/** Ends the session on the server as well as here, and never fails doing it. */
export async function endSession() {
  const refreshToken = getRefreshToken();
  clearSession();
  if (refreshToken) {
    try {
      await authService.logout(refreshToken);
    } catch (error) {
      // Signing out locally is the part the person asked for and it has already happened. A
      // server that could not be reached leaves a row that expires on its own.
      console.warn('[Auth] Could not end the session on the server:', error.message);
    }
  }
}
