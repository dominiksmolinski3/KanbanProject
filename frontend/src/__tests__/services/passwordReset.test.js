import { authService } from '../../services/authService';

/**
 * A forgotten password used to be an unrecoverable account. What these pin is the part a client
 * can get wrong on its own: the request goes as a body rather than a query string, so the address
 * does not land in an access log or a browser history the way `/auth/resend?email=` does, and the
 * 202 carries no body to parse.
 */
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
      window.fetch.mockResolvedValue(rejected(429, 'Too many requests'));

      await expect(authService.requestPasswordReset('a@example.test'))
        .rejects.toThrow('Too many requests');
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
      window.fetch.mockResolvedValue(rejected(400, 'Invalid password reset code'));

      await expect(authService.resetPassword('a@example.test', '000000', 'brand-new-password'))
        .rejects.toThrow('Invalid password reset code');
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
      // apiInterceptor skips any URL containing '/auth/'. A change-password route placed under
      // /auth/ would go out unauthenticated and be rejected, which is easy to do by accident.
      expect('/api/users/7/password').not.toContain('/auth/');
    });

    test('a wrong current password surfaces as an error rather than resolving', async () => {
      window.fetch.mockResolvedValue(rejected(401, 'Invalid email or password'));

      await expect(authService.changePassword(7, 'wrong', 'a-new-password'))
        .rejects.toThrow('Invalid email or password');
    });
  });
});
