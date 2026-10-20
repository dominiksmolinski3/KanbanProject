import React from 'react';
import { act, render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { AuthProvider, VERIFY_RETRY_MS, useAuth } from '../../context/AuthContext';
import { SessionExpiredError } from '../../services/apiInterceptor';
import * as session from '../../services/session';

jest.mock('../../services/session', () => ({
  TOKEN_KEY: 'token',
  getAccessToken: jest.fn(),
  storeSession: jest.fn(),
  clearSession: jest.fn(),
  endSession: jest.fn(() => Promise.resolve()),
}));

const TestComponent = () => {
  const auth = useAuth();

  if (auth.isLoading) return <div>Loading...</div>;

  return (
    <div>
      <div>Authenticated: {String(auth.isAuthenticated)}</div>
      <div>User: {auth.user ? auth.user.email : 'none'}</div>
      <button onClick={() => auth.login({ token: 'new-token', refreshToken: 'rt', expiresIn: 900000, sessionId: 's1' })}>
        Login
      </button>
      <button onClick={() => auth.logout()}>Logout</button>
    </div>
  );
};

describe('AuthContext', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    global.fetch = jest.fn();
  });

  test('with no stored token, resolves loading immediately and stays signed out', async () => {
    session.getAccessToken.mockReturnValue(null);

    render(<AuthProvider><TestComponent /></AuthProvider>);

    await waitFor(() => expect(screen.getByText('Authenticated: false')).toBeInTheDocument());
    expect(global.fetch).not.toHaveBeenCalled();
  });

  test('a stored token that verifies loads the account', async () => {
    session.getAccessToken.mockReturnValue('existing-token');
    global.fetch.mockResolvedValueOnce({
      ok: true,
      text: async () => JSON.stringify({ email: 'ada@example.com' }),
    });

    render(<AuthProvider><TestComponent /></AuthProvider>);

    await waitFor(() => expect(screen.getByText('User: ada@example.com')).toBeInTheDocument());
    expect(screen.getByText('Authenticated: true')).toBeInTheDocument();
    expect(global.fetch).toHaveBeenCalledWith('/api/users/me', {
      headers: { Authorization: 'Bearer existing-token' },
    });
  });

  test('a stored token the server rejects is forgotten', async () => {
    session.getAccessToken.mockReturnValue('stale-token');
    global.fetch.mockResolvedValueOnce({ ok: false, status: 401 });

    render(<AuthProvider><TestComponent /></AuthProvider>);

    await waitFor(() => expect(screen.getByText('Authenticated: false')).toBeInTheDocument());
    expect(session.clearSession).toHaveBeenCalled();
  });

  test('a session the interceptor could not renew is forgotten', async () => {
    session.getAccessToken.mockReturnValue('stale-token');
    global.fetch.mockRejectedValueOnce(new SessionExpiredError());

    render(<AuthProvider><TestComponent /></AuthProvider>);

    await waitFor(() => expect(screen.getByText('Authenticated: false')).toBeInTheDocument());
    expect(session.clearSession).toHaveBeenCalled();
  });

  test('a network failure keeps the session and checks again, rather than signing out every tab', async () => {
    jest.useFakeTimers();
    try {
      session.getAccessToken.mockReturnValue('some-token');
      global.fetch
        .mockRejectedValueOnce(new TypeError('Failed to fetch'))
        .mockResolvedValueOnce({ ok: true, text: async () => JSON.stringify({ email: 'ada@example.com' }) });
      const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});

      render(<AuthProvider><TestComponent /></AuthProvider>);

      await waitFor(() => expect(screen.getByText('Authenticated: true')).toBeInTheDocument());
      expect(screen.getByText('User: none')).toBeInTheDocument();
      expect(session.clearSession).not.toHaveBeenCalled();

      await act(async () => {
        jest.advanceTimersByTime(VERIFY_RETRY_MS);
      });

      await waitFor(() => expect(screen.getByText('User: ada@example.com')).toBeInTheDocument());
      expect(global.fetch).toHaveBeenCalledTimes(2);
      expect(session.clearSession).not.toHaveBeenCalled();
      errorSpy.mockRestore();
    } finally {
      jest.useRealTimers();
    }
  });

  test('a server error keeps the session too', async () => {
    session.getAccessToken.mockReturnValue('some-token');
    global.fetch.mockResolvedValue({ ok: false, status: 503 });

    render(<AuthProvider><TestComponent /></AuthProvider>);

    await waitFor(() => expect(screen.getByText('Authenticated: true')).toBeInTheDocument());
    expect(session.clearSession).not.toHaveBeenCalled();
  });

  test('login stores the whole session and signs in', async () => {
    session.getAccessToken.mockReturnValue(null);

    render(<AuthProvider><TestComponent /></AuthProvider>);
    await waitFor(() => expect(screen.getByText('Authenticated: false')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Login'));

    expect(session.storeSession).toHaveBeenCalledWith({
      token: 'new-token', refreshToken: 'rt', expiresIn: 900000, sessionId: 's1',
    });
    await waitFor(() => expect(screen.getByText('Authenticated: true')).toBeInTheDocument());
  });

  test('logout withdraws the refresh token on the server and clears local state', async () => {
    session.getAccessToken.mockReturnValue('existing-token');
    global.fetch.mockResolvedValueOnce({ ok: true, text: async () => '' });

    render(<AuthProvider><TestComponent /></AuthProvider>);
    await waitFor(() => expect(screen.getByText('Authenticated: true')).toBeInTheDocument());

    fireEvent.click(screen.getByText('Logout'));

    expect(session.endSession).toHaveBeenCalled();
    await waitFor(() => expect(screen.getByText('Authenticated: false')).toBeInTheDocument());
  });

  describe('another tab', () => {
    const signedIn = async () => {
      session.getAccessToken.mockReturnValue('existing-token');
      global.fetch.mockResolvedValueOnce({
        ok: true,
        text: async () => JSON.stringify({ email: 'ada@example.com' }),
      });
      render(<AuthProvider><TestComponent /></AuthProvider>);
      await waitFor(() => expect(screen.getByText('User: ada@example.com')).toBeInTheDocument());
    };

    const storageChanges = (key, newValue) =>
      act(() => {
        window.dispatchEvent(new StorageEvent('storage', { key, newValue }));
      });

    test('signing out there signs this tab out too, and drops its cached data', async () => {
      await signedIn();

      session.getAccessToken.mockReturnValue(null);
      storageChanges('token', null);

      expect(screen.getByText('Authenticated: false')).toBeInTheDocument();
      expect(screen.getByText('User: none')).toBeInTheDocument();
      expect(session.clearSession).toHaveBeenCalled();
    });

    test('clearing all of storage there counts as signing out', async () => {
      await signedIn();

      session.getAccessToken.mockReturnValue(null);
      storageChanges(null, null);

      expect(screen.getByText('Authenticated: false')).toBeInTheDocument();
    });

    test('renewing the session there leaves this tab signed in', async () => {
      await signedIn();

      session.getAccessToken.mockReturnValue('renewed-token');
      storageChanges('token', 'renewed-token');

      expect(screen.getByText('Authenticated: true')).toBeInTheDocument();
      expect(screen.getByText('User: ada@example.com')).toBeInTheDocument();
      expect(session.clearSession).not.toHaveBeenCalled();
    });
  });
});
