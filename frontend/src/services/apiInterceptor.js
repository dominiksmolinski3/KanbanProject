/**
 * Thrown instead of resolving with `undefined` when the stored token has already expired.
 *
 * Navigation does not interrupt the JavaScript that started it, so every caller awaiting the
 * patched `fetch` used to keep running against a response that was never made. Rejecting lets
 * the caller's own `catch` run, and lets it tell an expired session apart from a network error.
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

  window.fetch = async (input, options = {}) => {
    if (urlOf(input).includes('/auth/')) {
      return originalFetch(input, options);
    }

    const token = localStorage.getItem('token');

    if (!token) {
      return originalFetch(input, options);
    }

    const expiration = parseInt(localStorage.getItem('tokenExpiration'), 10);
    if (expiration && Date.now() > expiration) {
      localStorage.removeItem('token');
      localStorage.removeItem('tokenExpiration');
      window.location.href = '/';
      throw new SessionExpiredError();
    }

    return originalFetch(input, {
      ...options,
      headers: {
        // A default the caller can override — the avatar routes ask for `image/*`.
        'Accept': 'application/json',
        ...options.headers,
        'Authorization': `Bearer ${token}`
      }
    });
  };
}
