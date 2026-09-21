import { authService } from './authService';

/**
 * Where the two halves of a session live, and the one place that renews them: the access token is
 * a signed claim the server cannot take back, so it is short-lived, while the refresh token is a
 * row the server can withdraw and is what actually keeps someone signed in.
 */

export const TOKEN_KEY = 'token';
export const TOKEN_EXPIRY_KEY = 'tokenExpiration';
export const REFRESH_TOKEN_KEY = 'refreshToken';
/**
 * Which row on the server this browser's session is, so the device list can say "this device".
 * The client already gets the id at login and a new one on every rotation, cheaper than the
 * server marking it in the access token and looking it up per request; storing it matters because
 * the id changes roughly every fifteen minutes and localStorage is shared across tabs.
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
 * `expiresIn` is **milliseconds**, as `LoginResponse` sends it and every `/auth` test asserts —
 * `jwtService.getExpirationTime()` passed straight through. Treating it as seconds once put the
 * stored expiry ten days out, so `isAccessTokenExpired` never tripped and the token only ever
 * failed by reaching the server already dead.
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

/**
 * Sends the browser to the sign-in screen. A named seam rather than an inline
 * `window.location.href = '/'` because jsdom (21+) makes `window.location` unforgeable, the same as
 * a real browser does - nothing can stand in for it in a test, so the call itself is what a test
 * doubles instead.
 */
export function redirectToSignIn() {
  window.location.href = '/';
}

export const getAccessToken = () => localStorage.getItem(TOKEN_KEY);
export const getRefreshToken = () => localStorage.getItem(REFRESH_TOKEN_KEY);
export const getSessionId = () => localStorage.getItem(SESSION_ID_KEY);

export function isAccessTokenExpired() {
  const expiration = parseInt(localStorage.getItem(TOKEN_EXPIRY_KEY), 10);
  return Boolean(expiration) && Date.now() > expiration - EXPIRY_SKEW_MS;
}

/**
 * One renewal at a time, however many callers ask for it: a board load fires a dozen requests at
 * once, and since refresh tokens rotate, two concurrent refreshes would present the same spent
 * token twice - which the server reads as theft and withdraws every session on the account.
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
