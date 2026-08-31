import { authService } from '../../services/authService';

/**
 * Signup and resend now answer 202 with no body, whatever the address turns out to be. That is
 * only worth anything if the client stops trying to read one: `response.json()` on an empty body
 * throws, which would have turned a successful registration into an error toast and handed the
 * caller back the very distinction the uniform response exists to remove.
 */
describe('authService registration and resend', () => {
  const accepted = () => ({
    ok: true,
    status: 202,
    headers: { get: () => null },
    json: jest.fn(() => Promise.reject(new SyntaxError('Unexpected end of JSON input'))),
    text: jest.fn(() => Promise.resolve('')),
  });

  beforeEach(() => {
    window.fetch = jest.fn();
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  test('a 202 with an empty body resolves rather than failing to parse it', async () => {
    const response = accepted();
    window.fetch.mockResolvedValue(response);

    await expect(
      authService.register({ username: 'a', email: 'a@example.test', password: 'correct-horse' })
    ).resolves.toBeUndefined();

    expect(response.json).not.toHaveBeenCalled();
  });

  test('a new address and one that already has an account are handled identically', async () => {
    window.fetch.mockResolvedValue(accepted());

    const fresh = await authService.register({
      username: 'a', email: 'new@example.test', password: 'correct-horse',
    });
    const taken = await authService.register({
      username: 'a', email: 'taken@example.test', password: 'correct-horse',
    });

    expect(fresh).toEqual(taken);
  });

  test('a real failure still surfaces as an error', async () => {
    window.fetch.mockResolvedValue({
      ok: false,
      status: 429,
      text: () => Promise.resolve('Too many requests'),
    });

    await expect(
      authService.register({ username: 'a', email: 'a@example.test', password: 'correct-horse' })
    ).rejects.toThrow('Too many requests');
  });

  test('resend posts the address as a query parameter and resolves on 202', async () => {
    window.fetch.mockResolvedValue(accepted());

    await expect(authService.resendVerificationCode('a+b@example.test')).resolves.toBe('');

    const [url, options] = window.fetch.mock.calls[0];
    expect(url).toBe('/api/auth/resend?email=a%2Bb%40example.test');
    expect(options.method).toBe('POST');
  });

  test('the captcha token is still nested the way the server expects', async () => {
    window.fetch.mockResolvedValue(accepted());

    await authService.register({
      username: 'a', email: 'a@example.test', password: 'correct-horse', captchaToken: 'tok',
    });

    const body = JSON.parse(window.fetch.mock.calls[0][1].body);
    expect(body.captcha).toEqual({ token: 'tok' });
    expect(body.captchaToken).toBeUndefined();
  });
});

/**
 * Verifying answers 200 with a session now, and the client signs in with it. The 204 it used to
 * answer is still handled - a client can be newer than the server it is talking to - and the
 * caller has to be able to tell the two apart, which is what these two cases are.
 */
describe('authService verification', () => {
  beforeEach(() => {
    window.fetch = jest.fn();
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  test('a session in the response comes back parsed, so the caller can sign in with it', async () => {
    const session = {
      token: 'signed-access-token',
      expiresIn: 900000,
      refreshToken: 'a-refresh-token',
      refreshExpiresIn: 2592000000,
    };
    window.fetch.mockResolvedValue({
      ok: true,
      status: 200,
      headers: { get: () => 'application/json' },
      json: () => Promise.resolve(session),
      text: () => Promise.resolve(JSON.stringify(session)),
    });

    await expect(
      authService.verifyAccount({ email: 'a@example.test', verificationCode: '111111' })
    ).resolves.toEqual(session);

    const [url, options] = window.fetch.mock.calls[0];
    expect(url).toBe('/api/auth/verify');
    expect(options.method).toBe('POST');
  });

  test('an empty 204 resolves to something without a token, and is not mistaken for a session', async () => {
    window.fetch.mockResolvedValue({
      ok: true,
      status: 204,
      headers: { get: () => null },
      json: jest.fn(() => Promise.reject(new SyntaxError('Unexpected end of JSON input'))),
      text: () => Promise.resolve(''),
    });

    const response = await authService.verifyAccount({
      email: 'a@example.test', verificationCode: '111111',
    });

    expect(response && response.token).toBeFalsy();
  });

  test('a rejected code surfaces as an error rather than as an empty session', async () => {
    window.fetch.mockResolvedValue({
      ok: false,
      status: 400,
      text: () => Promise.resolve('Invalid verification code'),
    });

    await expect(
      authService.verifyAccount({ email: 'a@example.test', verificationCode: '222222' })
    ).rejects.toThrow('Invalid verification code');
  });
});
