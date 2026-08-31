import {
  clearSession,
  getAccessToken,
  getRefreshToken,
  isAccessTokenExpired,
  refreshSession
} from './session';

/**
 * Thrown instead of resolving with `undefined` when the session has ended for good.
 *
 * Navigation does not interrupt the JavaScript that started it, so every caller awaiting the
 * patched `fetch` used to keep running against a response that was never made. Rejecting lets
 * the caller's own `catch` run, and lets it tell an ended session apart from a network error.
 */
export class SessionExpiredError extends Error {
  constructor(message = 'Session expired') {
    super(message);
    this.name = 'SessionExpiredError';
  }
}

/**
 * `fetch` accepts a string, a `URL` or a `Request`; only the first has `.includes`.
 */
function urlOf(input) {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.href;
  return input?.url ?? '';
}

export function setupApiInterceptors() {
  const originalFetch = window.fetch;

  const send = (input, options, token) =>
    originalFetch(input, {
      ...options,
      headers: {
        // A default the caller can override — the avatar routes ask for `image/*`.
        'Accept': 'application/json',
        ...options.headers,
        'Authorization': `Bearer ${token}`
      }
    });

  /**
   * Renews the access token, or ends the session for good if it cannot be renewed.
   *
   * The redirect and the throw are what the expiry branch always did; what is new is the attempt
   * that comes first. An account with no refresh token — one signed in before this existed, or one
   * whose chain has been withdrawn — takes exactly the old path.
   */
  const renewOrEnd = async () => {
    try {
      const renewed = await refreshSession();
      if (renewed) return renewed;
    } catch {
      // refreshSession has already cleared what it stored; fall through to the same ending.
    }
    clearSession();
    window.location.href = '/';
    throw new SessionExpiredError();
  };

  window.fetch = async (input, options = {}) => {
    if (urlOf(input).includes('/auth/')) {
      return originalFetch(input, options);
    }

    let token = getAccessToken();

    if (!token && !getRefreshToken()) {
      return originalFetch(input, options);
    }

    if (!token || isAccessTokenExpired()) {
      token = await renewOrEnd();
    }

    const response = await send(input, options, token);

    /*
     * A 401 the client did not see coming: the token was withdrawn, or the server came back with a
     * different signing key. One retry, and only when there is a refresh token to retry with, so a
     * genuinely unauthorised request cannot loop. The request is replayed with the same `options`,
     * which is safe for the string and FormData bodies this app sends and would not be for a
     * one-shot stream.
     */
    if (response.status === 401 && getRefreshToken()) {
      return send(input, options, await renewOrEnd());
    }

    return response;
  };
}
