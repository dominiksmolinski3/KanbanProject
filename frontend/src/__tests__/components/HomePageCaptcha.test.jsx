import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import HomePage from '../../components/HomePage';
import { authService } from '../../services/authService';
import { useAuth } from '../../context/AuthContext';

process.env.VITE_RECAPTCHA_SITE_KEY = 'site-key';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, fallback) => fallback || key, i18n: { language: 'en' } }),
}));
jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn(), info: jest.fn() },
}));
jest.mock('../../services/authService', () => ({
  authService: { login: jest.fn(), register: jest.fn() },
}));
jest.mock('../../context/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('../../components/DemoBanner', () => () => <div>DemoBanner</div>);
jest.mock('../../components/LanguageSwitcher', () => () => <div>LanguageSwitcher</div>);
jest.mock('react-router-dom', () => ({ useNavigate: () => jest.fn() }));

const mockReset = jest.fn();
jest.mock('../../components/SafeReCAPTCHA', () => {
  const React = require('react');
  return React.forwardRef(function MockCaptcha({ onChange, onReady, onLoadError }, ref) {
    React.useImperativeHandle(ref, () => ({ reset: mockReset }));
    React.useEffect(() => { onReady?.(); }, []);
    return (
      <div>
        <button type="button" onClick={() => onChange('solved')}>solve</button>
        <button type="button" onClick={() => onLoadError(new Error('blocked'))}>fail to load</button>
      </div>
    );
  });
});

const fillCredentials = () => {
  fireEvent.change(screen.getByPlaceholderText('Email'), { target: { value: 'ada@example.com' } });
  fireEvent.change(screen.getByPlaceholderText('Password'), { target: { value: 'hunter22' } });
};

describe('HomePage with a captcha configured', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    useAuth.mockReturnValue({ token: null, isLoading: false, login: jest.fn(), logout: jest.fn() });
  });

  afterAll(() => {
    delete process.env.VITE_RECAPTCHA_SITE_KEY;
  });

  test('the sign-in button waits until the captcha is solved', () => {
    render(<HomePage />);

    expect(screen.getByText('Sign In')).toBeDisabled();
    fireEvent.click(screen.getByText('solve'));
    expect(screen.getByText('Sign In')).toBeEnabled();
  });

  test('the solved captcha goes to the server with the credentials', async () => {
    authService.login.mockResolvedValue({ token: 'jwt' });
    render(<HomePage />);

    fillCredentials();
    fireEvent.click(screen.getByText('solve'));
    fireEvent.click(screen.getByText('Sign In'));

    await waitFor(() => expect(authService.login).toHaveBeenCalledWith({
      email: 'ada@example.com',
      password: 'hunter22',
      captcha: { token: 'solved' },
    }));
  });

  test('a refused login clears the solved captcha and resets the widget', async () => {
    authService.login.mockRejectedValue(new Error('Bad credentials'));
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
    render(<HomePage />);

    fillCredentials();
    fireEvent.click(screen.getByText('solve'));
    fireEvent.click(screen.getByText('Sign In'));

    await waitFor(() => expect(screen.getByText('Bad credentials')).toBeInTheDocument());
    expect(mockReset).toHaveBeenCalledTimes(1);
    expect(screen.getByText('Sign In')).toBeDisabled();
    errorSpy.mockRestore();
  });

  test('a captcha that cannot load offers a retry', () => {
    render(<HomePage />);

    fireEvent.click(screen.getByText('fail to load'));
    expect(screen.getByText('auth.retry')).toBeInTheDocument();

    fireEvent.click(screen.getByText('auth.retry'));
    expect(screen.queryByText('auth.retry')).not.toBeInTheDocument();
  });
});
