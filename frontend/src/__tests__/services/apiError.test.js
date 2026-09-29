import fs from 'fs';
import path from 'path';
import i18next from 'i18next';
import { apiError, errorFromBody, ApiError } from '../../services/apiError';

const bundle = (language) => JSON.parse(fs.readFileSync(
  path.join(__dirname, '..', '..', '..', 'public', 'locales', language, 'translation.json'), 'utf8'));

const response = (status, body) => ({
  ok: false,
  status,
  text: () => Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body)),
});

describe('apiError', () => {
  describe('before the bundles are loaded', () => {
    test('uses the server sentence, never the body it came in', async () => {
      const error = await apiError(response(401, { code: 'INVALID_CREDENTIALS', message: 'Invalid email or password' }));

      expect(error).toBeInstanceOf(ApiError);
      expect(error).toMatchObject({ status: 401, code: 'INVALID_CREDENTIALS', message: 'Invalid email or password' });
    });

    test.each([
      ['an empty body', ''],
      ['an HTML error page', '<html><body>502 Bad Gateway</body></html>'],
      ['JSON with no message', { code: 'SOMETHING' }],
      ['JSON whose message is itself JSON', { message: '{"nested":true}' }],
      ['plain text', 'upstream connect error'],
    ])('falls back to a generic sentence for %s', async (_, body) => {
      const error = await apiError(response(500, body));

      expect(error.message).toBe('Something went wrong. Try again.');
      expect(error.status).toBe(500);
    });

    test('a response whose body was already read still yields an error', async () => {
      const used = { ok: false, status: 400, text: () => Promise.reject(new TypeError('Body is unusable')) };

      await expect(apiError(used)).resolves.toMatchObject({ status: 400, code: undefined });
    });
  });

  describe('with the bundles loaded', () => {
    beforeAll(async () => {
      await i18next.init({
        lng: 'pl',
        fallbackLng: 'en',
        resources: { en: { translation: bundle('en') }, pl: { translation: bundle('pl') } },
      });
    });

    afterAll(async () => {
      await i18next.changeLanguage('en');
    });

    test('translates the code into the reader language', async () => {
      const error = await apiError(response(401, { code: 'INVALID_CREDENTIALS', message: 'Invalid email or password' }));

      expect(error.message).toBe(bundle('pl').errors.codes.INVALID_CREDENTIALS);
    });

    test('an unknown code falls back by status, then to the generic sentence', () => {
      expect(errorFromBody(503, { code: 'NEW_CODE' }).message).toBe(bundle('pl').errors.status.server);
      expect(errorFromBody(413, null).message).toBe(bundle('pl').errors.status.tooLarge);
      expect(errorFromBody(400, { code: 'NEW_CODE', message: 'Raw' }).message).toBe(bundle('pl').errors.generic);
    });

    test('the edge rate limit reads like the application one', () => {
      expect(errorFromBody(429, null).message).toBe(bundle('pl').errors.codes.TOO_MANY_REQUESTS);
    });
  });
});

describe('the service layer', () => {
  const services = path.join(__dirname, '..', '..', 'services');
  const sources = fs.readdirSync(services)
    .filter((file) => file.endsWith('.js'))
    .map((file) => [file, fs.readFileSync(path.join(services, file), 'utf8')]);

  test.each(sources)('%s builds no error out of a raw response', (_, source) => {
    const constructed = [...source.matchAll(/new Error\(([^;]*)\)/g)].map((match) => match[1]);

    expect(constructed.filter((argument) => /response\.|errorData|\.text\(\)/.test(argument))).toEqual([]);
  });
});
