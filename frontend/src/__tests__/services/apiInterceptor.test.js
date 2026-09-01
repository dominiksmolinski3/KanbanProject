import { setupApiInterceptors, SessionExpiredError } from '../../services/apiInterceptor';
import { REFRESH_TOKEN_KEY, resetRefreshState } from '../../services/session';
import { authService } from '../../services/authService';

jest.mock('../../services/authService', () => ({
  authService: { refresh: jest.fn(), logout: jest.fn() }
}));

describe('apiInterceptor', () => {
  let originalFetch;
  let originalLocation;

  beforeEach(() => {
    originalFetch = jest.fn().mockResolvedValue({ ok: true, status: 200 });
    window.fetch = originalFetch;

    originalLocation = window.location;
    delete window.location;
    window.location = { href: '/board' };

    localStorage.clear();
    resetRefreshState();
    jest.clearAllMocks();
    setupApiInterceptors();
  });

  afterEach(() => {
    window.location = originalLocation;
    jest.restoreAllMocks();
  });

  const futureExpiry = () => String(Date.now() + 60_000);
  const pastExpiry = () => String(Date.now() - 60_000);

  test('passes auth requests through untouched', async () => {
    localStorage.setItem('token', 'jwt');
    localStorage.setItem('tokenExpiration', futureExpiry());

    await window.fetch('/api/auth/login', { method: 'POST' });

    expect(originalFetch).toHaveBeenCalledWith('/api/auth/login', { method: 'POST' });
  });

  test('attaches the bearer token to everything else', async () => {
    localStorage.setItem('token', 'jwt');
    localStorage.setItem('tokenExpiration', futureExpiry());

    await window.fetch('/api/tasks');

    expect(originalFetch).toHaveBeenCalledWith('/api/tasks', {
      headers: {
        'Accept': 'application/json',
        'Authorization': 'Bearer jwt'
      }
    });
  });

  test("does not overwrite the caller's Accept header", async () => {
    localStorage.setItem('token', 'jwt');
    localStorage.setItem('tokenExpiration', futureExpiry());

    await window.fetch('/api/users/1/avatar', { headers: { 'Accept': 'image/*' } });

    expect(originalFetch.mock.calls[0][1].headers).toEqual({
      'Accept': 'image/*',
      'Authorization': 'Bearer jwt'
    });
  });

  test('leaves the request alone when there is no token', async () => {
    await window.fetch('/api/tasks');

    expect(originalFetch).toHaveBeenCalledWith('/api/tasks', {});
  });

  test('rejects with SessionExpiredError once the token has expired', async () => {
    localStorage.setItem('token', 'jwt');
    localStorage.setItem('tokenExpiration', pastExpiry());

    await expect(window.fetch('/api/tasks')).rejects.toBeInstanceOf(SessionExpiredError);

    expect(originalFetch).not.toHaveBeenCalled();
    expect(localStorage.getItem('token')).toBeNull();
    expect(localStorage.getItem('tokenExpiration')).toBeNull();
    expect(window.location.href).toBe('/');
  });

  test('reads the URL out of a Request object instead of throwing', async () => {
    localStorage.setItem('token', 'jwt');
    localStorage.setItem('tokenExpiration', futureExpiry());

    await window.fetch({ url: 'http://localhost:8080/api/auth/login' });

    expect(originalFetch).toHaveBeenCalledWith(
      { url: 'http://localhost:8080/api/auth/login' },
      {}
    );
  });

  test('renews the session instead of ending it when there is a refresh token', async () => {
    localStorage.setItem('token', 'stale-jwt');
    localStorage.setItem('tokenExpiration', pastExpiry());
    localStorage.setItem(REFRESH_TOKEN_KEY, 'held');
    authService.refresh.mockResolvedValue({
      token: 'fresh-jwt',
      expiresIn: 900_000,
      refreshToken: 'rotated'
    });

    await window.fetch('/api/tasks');

    // The old behaviour here was a redirect to the sign-in screen every fifteen minutes, which is
    // what made a short access token unaffordable in the first place.
    expect(window.location.href).toBe('/board');
    expect(originalFetch.mock.calls[0][1].headers.Authorization).toBe('Bearer fresh-jwt');
  });

  test('ends the session when the refresh token is refused', async () => {
    localStorage.setItem('token', 'stale-jwt');
    localStorage.setItem('tokenExpiration', pastExpiry());
    localStorage.setItem(REFRESH_TOKEN_KEY, 'withdrawn');
    authService.refresh.mockRejectedValue(new Error('Invalid email or password'));

    await expect(window.fetch('/api/tasks')).rejects.toBeInstanceOf(SessionExpiredError);

    expect(originalFetch).not.toHaveBeenCalled();
    expect(localStorage.getItem(REFRESH_TOKEN_KEY)).toBeNull();
    expect(window.location.href).toBe('/');
  });

  test('retries once behind a 401 the client did not see coming', async () => {
    localStorage.setItem('token', 'revoked-jwt');
    localStorage.setItem('tokenExpiration', futureExpiry());
    localStorage.setItem(REFRESH_TOKEN_KEY, 'held');
    originalFetch
      .mockResolvedValueOnce({ ok: false, status: 401 })
      .mockResolvedValueOnce({ ok: true, status: 200 });
    authService.refresh.mockResolvedValue({
      token: 'fresh-jwt',
      expiresIn: 900_000,
      refreshToken: 'rotated'
    });

    const response = await window.fetch('/api/tasks');

    expect(response.status).toBe(200);
    expect(originalFetch).toHaveBeenCalledTimes(2);
    expect(originalFetch.mock.calls[1][1].headers.Authorization).toBe('Bearer fresh-jwt');
  });

  test('a 401 with no refresh token is handed back rather than retried forever', async () => {
    localStorage.setItem('token', 'jwt');
    localStorage.setItem('tokenExpiration', futureExpiry());
    originalFetch.mockResolvedValue({ ok: false, status: 401 });

    const response = await window.fetch('/api/tasks');

    expect(response.status).toBe(401);
    expect(originalFetch).toHaveBeenCalledTimes(1);
    expect(authService.refresh).not.toHaveBeenCalled();
  });

  test('a request made with only a refresh token in hand renews before it is sent', async () => {
    localStorage.setItem(REFRESH_TOKEN_KEY, 'held');
    authService.refresh.mockResolvedValue({
      token: 'fresh-jwt',
      expiresIn: 900_000,
      refreshToken: 'rotated'
    });

    await window.fetch('/api/tasks');

    expect(originalFetch.mock.calls[0][1].headers.Authorization).toBe('Bearer fresh-jwt');
  });

  test('does not clone the response it returns', async () => {
    const clone = jest.fn();
    originalFetch.mockResolvedValue({ ok: true, clone });
    localStorage.setItem('token', 'jwt');
    localStorage.setItem('tokenExpiration', futureExpiry());

    const response = await window.fetch('/api/tasks');

    expect(clone).not.toHaveBeenCalled();
    expect(response.ok).toBe(true);
  });
});
