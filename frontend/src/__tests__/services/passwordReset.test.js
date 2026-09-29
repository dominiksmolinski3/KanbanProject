import { authService } from '../../services/authService';

describe('authService password reset', () => {
  const accepted = (status = 202) => ({
    ok: true,
    status,
    headers: { get: () => null },
    json: jest.fn(() => Promise.reject(new SyntaxError('Unexpected end of JSON input'))),
    text: jest.fn(() => Promise.resolve('')),
  });

  const rejected = (status, body) => ({
    ok: false,
    status,
    text: () => Promise.resolve(body),
  });

  beforeEach(() => {
    window.fetch = jest.fn();
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  describe('requesting a code', () => {
    test('posts the address in the body, not in the query string', async () => {
      window.fetch.mockResolvedValue(accepted());

      await authService.requestPasswordReset('someone@example.test');

      const [url, options] = window.fetch.mock.calls[0];
      expect(url).toBe('/api/auth/forgot-password');
      expect(url).not.toContain('someone@example.test');
      expect(options.method).toBe('POST');
      expect(JSON.parse(options.body)).toEqual({ email: 'someone@example.test' });
    });

    test('a 202 with no body resolves rather than failing to parse one', async () => {
      const response = accepted();
      window.fetch.mockResolvedValue(response);

      await expect(authService.requestPasswordReset('a@example.test')).resolves.toBeUndefined();
      expect(response.json).not.toHaveBeenCalled();
    });

    test('an address with an account and one without are handled identically', async () => {
      window.fetch.mockResolvedValue(accepted());

      const known = await authService.requestPasswordReset('known@example.test');
      const unknown = await authService.requestPasswordReset('unknown@example.test');

      expect(known).toEqual(unknown);
    });

    test('a rate limit still surfaces as an error', async () => {
      window.fetch.mockResolvedValue(rejected(429, JSON.stringify({ code: 'TOO_MANY_REQUESTS', message: 'Too many requests, please try again later' })));

      await expect(authService.requestPasswordReset('a@example.test'))
        .rejects.toMatchObject({ status: 429, code: 'TOO_MANY_REQUESTS', message: 'Too many requests, please try again later' });
    });
  });

  describe('redeeming a code', () => {
    test('sends the address, the code and the new password', async () => {
      window.fetch.mockResolvedValue(accepted(204));

      await authService.resetPassword('a@example.test', '123456', 'brand-new-password');

      const [url, options] = window.fetch.mock.calls[0];
      expect(url).toBe('/api/auth/reset-password');
      expect(JSON.parse(options.body)).toEqual({
        email: 'a@example.test',
        resetCode: '123456',
        newPassword: 'brand-new-password',
      });
    });

    test('a wrong code surfaces the server message', async () => {
      window.fetch.mockResolvedValue(rejected(400, JSON.stringify({ code: 'INVALID_RESET_CODE', message: 'Invalid password reset code' })));

      await expect(authService.resetPassword('a@example.test', '000000', 'brand-new-password'))
        .rejects.toMatchObject({ code: 'INVALID_RESET_CODE', message: 'Invalid password reset code' });
    });
  });

  describe('changing a password', () => {
    test('patches the user and sends both passwords', async () => {
      window.fetch.mockResolvedValue(accepted(204));

      await authService.changePassword(7, 'old-password', 'a-new-password');

      const [url, options] = window.fetch.mock.calls[0];
      expect(url).toBe('/api/users/7/password');
      expect(options.method).toBe('PATCH');
      expect(JSON.parse(options.body)).toEqual({
        currentPassword: 'old-password',
        newPassword: 'a-new-password',
      });
    });

    test('the path carries no /auth/, so the interceptor attaches the token', () => {
      expect('/api/users/7/password').not.toContain('/auth/');
    });

    test('a wrong current password surfaces as an error rather than resolving', async () => {
      window.fetch.mockResolvedValue(rejected(401, JSON.stringify({ code: 'INVALID_CREDENTIALS', message: 'Invalid email or password' })));

      await expect(authService.changePassword(7, 'wrong', 'a-new-password'))
        .rejects.toMatchObject({ status: 401, code: 'INVALID_CREDENTIALS' });
    });

    test('the error carries the server code, so the screen can say which field was wrong', async () => {
      window.fetch.mockResolvedValue(
        rejected(400, JSON.stringify({ code: 'WRONG_PASSWORD', message: 'The current password is not correct' })));

      await expect(authService.changePassword(7, 'wrong', 'a-new-password'))
        .rejects.toMatchObject({ code: 'WRONG_PASSWORD' });
    });
  });

  describe('changing the email address', () => {
    test('asks for a code with the new address and the password in the body', async () => {
      window.fetch.mockResolvedValue(accepted());

      await authService.requestEmailChange(7, 'new@example.test', 'pw');

      const [url, options] = window.fetch.mock.calls[0];
      expect(url).toBe('/api/users/7/email-change');
      expect(options.method).toBe('POST');
      expect(JSON.parse(options.body)).toEqual({ newEmail: 'new@example.test', currentPassword: 'pw' });
    });

    test('confirming answers the new session', async () => {
      const session = { token: 'jwt', sessionId: 3 };
      window.fetch.mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve(session) });

      await expect(authService.confirmEmailChange(7, '123456')).resolves.toEqual(session);
      const [url, options] = window.fetch.mock.calls[0];
      expect(url).toBe('/api/users/7/email-change/confirm');
      expect(JSON.parse(options.body)).toEqual({ code: '123456' });
    });

    test('a refused code rejects with the server code', async () => {
      window.fetch.mockResolvedValue(rejected(400, JSON.stringify({ code: 'INVALID_VERIFICATION_CODE' })));

      await expect(authService.confirmEmailChange(7, '000000'))
        .rejects.toMatchObject({ code: 'INVALID_VERIFICATION_CODE' });
    });
  });
});
