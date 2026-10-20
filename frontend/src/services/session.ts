import { authService } from './authService';
import { ApiError } from './apiError';
import type { LoginResponse } from '../types/api';

export const TOKEN_KEY = 'token';
export const TOKEN_EXPIRY_KEY = 'tokenExpiration';
export const REFRESH_TOKEN_KEY = 'refreshToken';
export const SESSION_ID_KEY = 'sessionId';

const EXPIRY_SKEW_MS = 10_000;

export type StoredSession = Pick<LoginResponse, 'token' | 'expiresIn'> & Partial<Pick<LoginResponse, 'refreshToken' | 'sessionId'>>;

export function storeSession({ token, expiresIn, refreshToken, sessionId }: StoredSession) {
  localStorage.setItem(TOKEN_KEY, token);
  // expiresIn is milliseconds.
  localStorage.setItem(TOKEN_EXPIRY_KEY, String(Date.now() + expiresIn));
  if (refreshToken) {
    localStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);
  }
  if (sessionId !== undefined && sessionId !== null) {
    localStorage.setItem(SESSION_ID_KEY, String(sessionId));
  }
}

const clearListeners = new Set<() => void>();

export const onSessionCleared = (listener: () => void) => {
  clearListeners.add(listener);
  return () => clearListeners.delete(listener);
};

export function clearSession() {
  localStorage.removeItem(TOKEN_KEY);
  localStorage.removeItem(TOKEN_EXPIRY_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  localStorage.removeItem(SESSION_ID_KEY);
  clearListeners.forEach(listener => listener());
}

export function redirectToSignIn() {
  window.location.href = '/';
}

export const getAccessToken = () => localStorage.getItem(TOKEN_KEY);
export const getRefreshToken = () => localStorage.getItem(REFRESH_TOKEN_KEY);
export const getSessionId = () => localStorage.getItem(SESSION_ID_KEY);

export function isAccessTokenExpired() {
  const expiration = parseInt(localStorage.getItem(TOKEN_EXPIRY_KEY) ?? '', 10);
  return Boolean(expiration) && Date.now() > expiration - EXPIRY_SKEW_MS;
}

export const isRefreshRejected = (error: unknown) =>
  error instanceof ApiError && (error.status === 400 || error.status === 401);

export const REFRESH_LOCK = 'kanban-session-refresh';

// One refresh at a time in every tab: rotation reads the same refresh token presented twice as theft
// and withdraws every session the account holds.
let inFlight: Promise<string | null> | null = null;

export function refreshSession(): Promise<string | null> {
  const presented = getRefreshToken();
  if (!presented) {
    return Promise.resolve(null);
  }
  if (!inFlight) {
    inFlight = acrossTabs(() => renew(presented)).finally(() => {
      inFlight = null;
    });
  }
  return inFlight;
}

function acrossTabs(task: () => Promise<string | null>): Promise<string | null> {
  const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
  return locks ? locks.request(REFRESH_LOCK, task) : task();
}

async function renew(presented: string): Promise<string | null> {
  const current = getRefreshToken();
  if (current !== presented) {
    return current ? getAccessToken() : null;
  }
  try {
    const session = await authService.refresh(current);
    storeSession(session);
    return session.token;
  } catch (error) {
    if (isRefreshRejected(error)) {
      clearSession();
    }
    throw error;
  }
}

export function resetRefreshState() {
  inFlight = null;
}

export async function endSession() {
  const refreshToken = getRefreshToken();
  clearSession();
  if (refreshToken) {
    try {
      await authService.logout(refreshToken);
    } catch (error) {
      console.warn('[Auth] Could not end the session on the server:', (error as Error).message);
    }
  }
}
