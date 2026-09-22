import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { AuthProvider, useAuth } from '../../context/AuthContext';
import * as session from '../../services/session';

jest.mock('../../services/session', () => ({
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
    global.fetch.mockResolvedValueOnce({ ok: false });

    render(<AuthProvider><TestComponent /></AuthProvider>);

    await waitFor(() => expect(screen.getByText('Authenticated: false')).toBeInTheDocument());
    expect(session.clearSession).toHaveBeenCalled();
  });

  test('a network failure verifying the token also forgets it', async () => {
    session.getAccessToken.mockReturnValue('some-token');
    global.fetch.mockRejectedValueOnce(new Error('network down'));
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});

    render(<AuthProvider><TestComponent /></AuthProvider>);

    await waitFor(() => expect(screen.getByText('Authenticated: false')).toBeInTheDocument());
    expect(session.clearSession).toHaveBeenCalled();
    errorSpy.mockRestore();
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
});
