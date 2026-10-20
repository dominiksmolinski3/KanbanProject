import { createContext, useState, useEffect, useContext, type ReactNode } from 'react';
import { TOKEN_KEY, clearSession, endSession, getAccessToken, storeSession, type StoredSession } from '../services/session';
import type { User } from '../types/api';

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
    const verifyToken = async () => {
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

        if (response.ok) {
          const text = await response.text();
          if (text) {
            const userData = JSON.parse(text);
            setUser(userData);
          } else {
            setUser(null);
          }
        } else {
          forget();
        }
      } catch (error) {
        console.error('Error verifying token:', error);
        forget();
      }

      setIsLoading(false);
    };

    verifyToken();
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
