import { createContext, useState, useEffect, useContext, type ReactNode } from 'react';
import { TOKEN_KEY, clearSession, endSession, getAccessToken, storeSession, type StoredSession } from '../services/session';
import { SessionExpiredError } from '../services/apiInterceptor';
import type { User } from '../types/api';

export const VERIFY_ATTEMPTS = 3;
export const VERIFY_RETRY_MS = 5_000;

interface AuthValue {
  token: string | null;
  user: User | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (session: StoredSession) => void;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthValue | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState(getAccessToken());
  const [user, setUser] = useState<User | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  useEffect(() => {
    const signedOutInAnotherTab = (event: StorageEvent) => {
      if ((event.key === TOKEN_KEY || event.key === null) && !getAccessToken()) {
        clearSession();
        setToken(null);
        setUser(null);
      }
    };
    window.addEventListener('storage', signedOutInAnotherTab);
    return () => window.removeEventListener('storage', signedOutInAnotherTab);
  }, []);

  useEffect(() => {
    let cancelled = false;
    let retry: ReturnType<typeof setTimeout> | undefined;
    const retryLater = (attempt: number) => {
      if (attempt < VERIFY_ATTEMPTS) {
        retry = setTimeout(() => verifyToken(attempt + 1), VERIFY_RETRY_MS);
      }
    };

    // Only a refusal of the session ends it: a network failure or a page unloading mid-request is not one.
    const verifyToken = async (attempt: number) => {
      if (!token) {
        setIsLoading(false);
        return;
      }

      try {
        const response = await fetch('/api/users/me', {
          headers: {
            'Authorization': `Bearer ${token}`
          }
        });
        if (cancelled) return;

        if (response.ok) {
          const text = await response.text();
          setUser(text ? JSON.parse(text) : null);
        } else if (response.status === 401 || response.status === 403) {
          forget();
        } else {
          retryLater(attempt);
        }
      } catch (error) {
        if (cancelled) return;
        if (error instanceof SessionExpiredError) {
          forget();
        } else {
          console.error('Error verifying token:', error);
          retryLater(attempt);
        }
      }

      if (!cancelled) setIsLoading(false);
    };

    verifyToken(1);
    return () => {
      cancelled = true;
      clearTimeout(retry);
    };
  }, [token]);

  const login = (session: StoredSession) => {
    storeSession(session);
    setToken(session.token);
  };

  const forget = () => {
    clearSession();
    setToken(null);
    setUser(null);
  };

  const logout = async () => {
    setToken(null);
    setUser(null);
    await endSession();
  };

  const value: AuthValue = {
    token,
    user,
    isAuthenticated: !!token,
    isLoading,
    login,
    logout
  };

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  );
}

export const useAuth = (): AuthValue => {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
};
