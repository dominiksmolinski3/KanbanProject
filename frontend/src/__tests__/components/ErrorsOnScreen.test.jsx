import React from 'react';
import fs from 'fs';
import path from 'path';
import { render, fireEvent, waitFor } from '@testing-library/react';
import i18next from 'i18next';
import { initReactI18next } from 'react-i18next';
import { toast } from 'react-toastify';
import HomePage from '../../components/HomePage';
import { useAuth } from '../../context/AuthContext';

jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn(), info: jest.fn() },
}));
jest.mock('../../context/AuthContext', () => ({
  useAuth: jest.fn(),
}));
jest.mock('../../components/DemoBanner', () => () => null);
jest.mock('../../components/LanguageSwitcher', () => () => null);
jest.mock('../../components/SafeReCAPTCHA', () => () => null);
jest.mock('react-router-dom', () => ({
  useNavigate: () => jest.fn(),
}));

const bundle = (language) => JSON.parse(fs.readFileSync(
  path.join(__dirname, '..', '..', '..', 'public', 'locales', language, 'translation.json'), 'utf8'));

const refuse = (status, body) => {
  window.fetch = jest.fn(() => Promise.resolve({
    ok: false,
    status,
    text: () => Promise.resolve(typeof body === 'string' ? body : JSON.stringify(body)),
  }));
};

const signIn = (container) => {
  fireEvent.change(container.querySelector('input[type="email"]'), { target: { value: 'ada@example.com' } });
  fireEvent.change(container.querySelector('input[type="password"]'), { target: { value: 'wrong' } });
  fireEvent.submit(container.querySelector('form'));
};

const shownError = async (container) => {
  await waitFor(() => expect(container.querySelector('.error-message')).not.toBeNull());
  return container.querySelector('.error-message').textContent;
};

describe('an error the server answers with, on screen', () => {
  beforeAll(async () => {
    await i18next.use(initReactI18next).init({
      lng: 'en',
      fallbackLng: 'en',
      resources: Object.fromEntries(['en', 'pl', 'de'].map((l) => [l, { translation: bundle(l) }])),
    });
  });

  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(console, 'error').mockImplementation(() => {});
    useAuth.mockReturnValue({ token: null, isLoading: false, login: jest.fn(), logout: jest.fn() });
  });

  afterEach(async () => {
    console.error.mockRestore();
    await i18next.changeLanguage('en');
  });

  test.each(['en', 'pl', 'de'])('a wrong password reads as a sentence in %s, never as the JSON body', async (language) => {
    await i18next.changeLanguage(language);
    refuse(401, { code: 'INVALID_CREDENTIALS', message: 'Invalid email or password' });
    const { container } = render(<HomePage />);

    signIn(container);

    const text = await shownError(container);
    expect(text).toBe(bundle(language).errors.codes.INVALID_CREDENTIALS);
    const toasted = toast.error.mock.calls.map(([message]) => message).join('\n');
    expect(toasted).toContain(bundle(language).errors.codes.INVALID_CREDENTIALS);
    expect(toasted).not.toMatch(/[{}]/);
  });

  test.each([
    ['the edge rate limit', 429, { code: 'TOO_MANY_REQUESTS', message: 'Too many requests, please try again later' }],
    ['a code this client has never heard of', 400, { code: 'SOMETHING_NEW', message: 'raw' }],
    ['an HTML gateway page', 502, '<html><body><h1>502 Bad Gateway</h1></body></html>'],
    ['a body that is not JSON', 500, '{"truncated'],
  ])('%s is shown as a sentence', async (_, status, body) => {
    refuse(status, body);
    const { container } = render(<HomePage />);

    signIn(container);

    const text = await shownError(container);
    expect(text).not.toMatch(/^\s*[{<[]/);
    expect(text).not.toContain('code');
    expect(text.length).toBeGreaterThan(0);
  });
});
