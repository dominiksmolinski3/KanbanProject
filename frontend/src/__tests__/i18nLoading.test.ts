import type { i18n as I18n } from 'i18next';
import { LANGUAGES } from '../languages';

let mockBrowserLanguage = 'en';
const mockRequested: string[] = [];

jest.mock('i18next-http-backend', () => ({
  __esModule: true,
  default: {
    type: 'backend',
    init: () => {},
    read: (language: string, _namespace: string, callback: (error: null, data: object) => void) => {
      mockRequested.push(language);
      callback(null, {});
    },
  },
}));

jest.mock('i18next-browser-languagedetector', () => ({
  __esModule: true,
  default: {
    type: 'languageDetector',
    init: () => {},
    detect: () => mockBrowserLanguage,
    cacheUserLanguage: () => {},
  },
}));

const shipped = LANGUAGES.map(({ code }) => code);

async function bundlesRequestedFor(browserLanguage: string): Promise<string[]> {
  mockBrowserLanguage = browserLanguage;
  mockRequested.length = 0;
  let i18n: I18n | undefined;
  await jest.isolateModulesAsync(async () => {
    i18n = (await import('../i18n')).default;
  });
  await new Promise<void>((resolve) => {
    if (i18n?.isInitialized) resolve();
    else i18n?.on('initialized', () => resolve());
  });
  return [...mockRequested];
}

describe('the locale bundles the app asks for', () => {
  test.each(['en-US', 'pl-PL', 'de', 'zh-CN', 'pt-BR'])(
    'a %s browser asks only for bundles that ship',
    async (browserLanguage) => {
      const requested = await bundlesRequestedFor(browserLanguage);

      expect(requested.length).toBeGreaterThan(0);
      expect(requested.filter((language) => !shipped.includes(language))).toEqual([]);
    }
  );

  test('a regional browser language gets the bundle for its language, not English', async () => {
    expect(await bundlesRequestedFor('pl-PL')).toContain('pl');
  });
});
