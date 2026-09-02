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
 * Which row on the server this browser's session is.
 *
 * It exists so the device list can say "this device". The server could mark it instead, but
 * only by putting the chain in the access token and looking it up on every request; the client
 * was handed the id at login and is handed a new one on every rotation, so it already knows.
 * Rotation is what makes storing it necessary rather than optional - the id changes roughly
 * every fifteen minutes of use, and localStorage is shared across tabs, so whichever tab
 * renewed last leaves the current answer here for all of them.
 */
export const SESSION_ID_KEY = 'sessionId';

/**
 * Renew this long before the access token actually lapses.
 *
 * A request that leaves with a token expiring in 200ms can still arrive after it has, and the
 * server is right to reject it. Treating the last few seconds as already gone costs one early
 * refresh and removes a race nobody can reproduce.
 */
const EXPIRY_SKEW_MS = 10_000;

/**
 * `expiresIn` is **milliseconds**, as `LoginResponse` sends it and every `/auth` test asserts it —
 * it is `jwtService.getExpirationTime()` passed straight through. Multiplying it by 1000 (reading it
 * as seconds) put the stored expiry ten days out, so `isAccessTokenExpired` never tripped and the
 * proactive renewal in the interceptor never ran: the fifteen-minute token only ever failed by
 * reaching the server dead, which is a 401 to recover from at best and a 500 at worst.
 */
export function storeSession({ token, expiresIn, refreshToken, sessionId }) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(TOKEN_EXPIRY_KEY, String(Date.now() + expiresIn));
  if (refreshToken) {
    localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
  }
  // Absent from a response issued before the server sent one; leaving the stored value alone is
  // better than clearing it, because the alternative is a list where nothing is "this device".
  if (sessionId !== undefined && sessionId !== null) {
    localStorage.setItem(SESSION_ID_KEY, String(sessionId));
  }
}

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXPIRY_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(SESSION_ID_KEY);
}

export const getAccessToken = () => localStorage.getItem(TOKEN_KEY);
export const getRefreshToken = () => localStorage.getItem(REFRESH_TOKEN_KEY);
export const getSessionId = () => localStorage.getItem(SESSION_ID_KEY);

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
