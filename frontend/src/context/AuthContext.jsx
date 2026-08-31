import React, { createContext, useState, useEffect, useContext } from 'react';
import { clearSession, endSession, getAccessToken, storeSession } from '../services/session';

const AuthContext = createContext();

export function AuthProvider({ children }) {
  const [token, setToken] = useState(getAccessToken());
  const [user, setUser] = useState(null);
  const [isLoading, setIsLoading] = useState(true);

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

  /**
   * Takes the whole login response rather than the two fields it used to, because there are three
   * now and the third one is the session. A response with no `refreshToken` — a server that has not
   * been deployed yet — still signs in and simply cannot be renewed, which is the behaviour that
   * was there before.
   */
  const login = (session) => {
    storeSession(session);
    setToken(session.token);
  };

  /** Drops what is held here without telling the server, for a session that is already over. */
  const forget = () => {
    clearSession();
    setToken(null);
    setUser(null);
  };

  /**
   * Signing out deliberately, which now means something on the server: the refresh token is
   * withdrawn, so the session cannot be renewed by whoever else may hold it. The access token still
   * runs to its own expiry — fifteen minutes — because nothing can retract a signed claim.
   */
  const logout = async () => {
    setToken(null);
    setUser(null);
    await endSession();
  };

  const value = {
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

export const useAuth = () => useContext(AuthContext);
