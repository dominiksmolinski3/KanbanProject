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
 * The auth routes that are reachable without a token, and so must not carry one.
 *
 * This used to be `url.includes('/auth/')` - every route under the prefix, on the assumption that
 * everything there is pre-authentication. `/auth/devices` is not: it lists an account's sessions,
 * so proving whose account it is is the entire precondition, and a blanket skip sent it out with
 * no `Authorization` header and no way to tell why the server refused.
 *
 * It is the same list `PublicPaths` holds on the server, and the same failure the server-side
 * version was written to end - one copy of the rule drifting away from the other. Keeping them in
 * step is a manual job across two languages; what makes that survivable is that the cost of
 * forgetting is now a 401 on one route rather than a token attached to a public one.
 */
const PUBLIC_AUTH_PATHS = [
  '/auth/signup',
  '/auth/login',
  '/auth/verify',
  '/auth/resend',
  '/auth/forgot-password',
  '/auth/reset-password',
  '/auth/refresh',
  '/auth/logout'
];

const isPublicAuthPath = (url) => PUBLIC_AUTH_PATHS.some((path) => url.includes(path));

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
    if (isPublicAuthPath(urlOf(input))) {
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
