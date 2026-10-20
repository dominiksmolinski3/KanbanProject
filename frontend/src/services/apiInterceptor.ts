import { apiUrl } from './apiOrigin';
import {
  clearSession,
  getAccessToken,
  getRefreshToken,
  isAccessTokenExpired,
  isRefreshRejected,
  redirectToSignIn,
  refreshSession
} from './session';

export class SessionExpiredError extends Error {
  constructor(message = 'Session expired') {
    super(message);
    this.name = 'SessionExpiredError';
  }
}

const isPublicAuthPath = (url: string) => url.includes('/api/auth/');

function urlOf(input: RequestInfo | URL): string {
  if (typeof input === 'string') return input;
  if (input instanceof URL) return input.href;
  return input?.url ?? '';
}

export async function renewOrSignOut(): Promise<string | null> {
  try {
    const renewed = await refreshSession();
    if (renewed) return renewed;
  } catch (error) {
    if (!isRefreshRejected(error)) throw error;
  }
  clearSession();
  redirectToSignIn();
  return null;
}

export function setupApiInterceptors() {
  const originalFetch = window.fetch;

  const send = (input: RequestInfo | URL, options: RequestInit, token: string) =>
    originalFetch(input, {
      ...options,
      headers: {
        'Accept': 'application/json',
        ...(options.headers as Record<string, string> | undefined),
        'Authorization': `Bearer ${token}`
      }
    });

  const renewOrEnd = async () => {
    const renewed = await renewOrSignOut();
    if (renewed) return renewed;
    throw new SessionExpiredError();
  };

  window.fetch = async (request: RequestInfo | URL, options: RequestInit = {}) => {
    const input = typeof request === 'string' ? apiUrl(request) : request;
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
