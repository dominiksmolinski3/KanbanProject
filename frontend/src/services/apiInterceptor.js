import {
  clearSession,
  getAccessToken,
  getRefreshToken,
  isAccessTokenExpired,
  redirectToSignIn,
  refreshSession
} from './session';

export class SessionExpiredError extends Error {
  constructor(message = 'Session expired') {
    super(message);
    this.name = 'SessionExpiredError';
  }
}

// Kept in step by hand with PublicPaths.AUTH_ENDPOINTS on the server.
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
        'Accept': 'application/json',
        ...options.headers,
        'Authorization': `Bearer ${token}`
      }
    });

  const renewOrEnd = async () => {
    try {
      const renewed = await refreshSession();
      if (renewed) return renewed;
    } catch {
      // refreshSession has already cleared what it stored; fall through to the same ending.
    }
    clearSession();
    redirectToSignIn();
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

    if (response.status === 401 && getRefreshToken()) {
      return send(input, options, await renewOrEnd());
    }

    return response;
  };
}
