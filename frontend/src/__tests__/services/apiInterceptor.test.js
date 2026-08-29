import { setupApiInterceptors, SessionExpiredError } from '../../services/apiInterceptor';

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
