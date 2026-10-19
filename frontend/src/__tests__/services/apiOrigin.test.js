import { apiOrigin, apiUrl, socketOrigin } from '../../services/apiOrigin';
import { setupApiInterceptors } from '../../services/apiInterceptor';
import { resetRefreshState } from '../../services/session';

jest.mock('../../services/authService', () => ({
  authService: { refresh: jest.fn(), logout: jest.fn() }
}));

describe('with no API origin configured', () => {
  beforeEach(() => {
    delete process.env.VITE_API_ORIGIN;
  });

  test('paths stay relative to the page', () => {
    expect(apiOrigin()).toBe('');
    expect(apiUrl('/api/tasks')).toBe('/api/tasks');
  });

  test('sockets use the page origin', () => {
    expect(socketOrigin()).toBe(window.location.origin);
  });
});

describe('with an API origin configured', () => {
  beforeEach(() => {
    process.env.VITE_API_ORIGIN = 'http://kanban.example.test/';
  });

  afterEach(() => {
    delete process.env.VITE_API_ORIGIN;
  });

  test('drops a trailing slash', () => {
    expect(apiOrigin()).toBe('http://kanban.example.test');
  });

  test('puts the origin in front of a server path', () => {
    expect(apiUrl('/api/tasks?x=1')).toBe('http://kanban.example.test/api/tasks?x=1');
  });

  test('leaves anything but an API path alone, so the app still loads its own files', () => {
    expect(apiUrl('/locales/en/translation.json')).toBe('/locales/en/translation.json');
    expect(apiUrl('/apiary')).toBe('/apiary');
    expect(apiUrl('/api')).toBe('http://kanban.example.test/api');
  });

  test('leaves an absolute or relative URL alone', () => {
    expect(apiUrl('http://elsewhere.test/a')).toBe('http://elsewhere.test/a');
    expect(apiUrl('blob:abc')).toBe('blob:abc');
  });

  test('sockets connect to it rather than to the page', () => {
    expect(socketOrigin()).toBe('http://kanban.example.test');
  });
});

describe('the fetch wrapper with an API origin configured', () => {
  let originalFetch;

  beforeEach(() => {
    process.env.VITE_API_ORIGIN = 'http://kanban.example.test';
    originalFetch = jest.fn().mockResolvedValue({ ok: true, status: 200 });
    window.fetch = originalFetch;
    localStorage.clear();
    resetRefreshState();
    setupApiInterceptors();
  });

  afterEach(() => {
    delete process.env.VITE_API_ORIGIN;
  });

  test('sends a signed-in request to the API origin with its token', async () => {
    localStorage.setItem('token', 'jwt');
    localStorage.setItem('tokenExpiration', String(Date.now() + 60_000));

    await window.fetch('/api/tasks');

    expect(originalFetch).toHaveBeenCalledTimes(1);
    const [url, options] = originalFetch.mock.calls[0];
    expect(url).toBe('http://kanban.example.test/api/tasks');
    expect(options.headers.Authorization).toBe('Bearer jwt');
  });

  test('sends an auth request to the API origin and still without a token', async () => {
    localStorage.setItem('token', 'jwt');
    localStorage.setItem('tokenExpiration', String(Date.now() + 60_000));

    await window.fetch('/api/auth/login', { method: 'POST' });

    expect(originalFetch).toHaveBeenCalledWith('http://kanban.example.test/api/auth/login', { method: 'POST' });
  });

  test('sends a signed-out request to the API origin too', async () => {
    await window.fetch('/api/users/me');

    expect(originalFetch).toHaveBeenCalledWith('http://kanban.example.test/api/users/me', {});
  });

  test('does not touch a request for a file served by the app itself', async () => {
    await window.fetch('/locales/en/translation.json');

    expect(originalFetch).toHaveBeenCalledWith('/locales/en/translation.json', {});
  });
});
