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

  const login = (session) => {
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
