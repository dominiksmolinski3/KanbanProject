import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { toast } from 'react-toastify';
import HomePage from '../../components/HomePage';
import { authService } from '../../services/authService';
import { useAuth } from '../../context/AuthContext';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, fallback) => fallback || key, i18n: { language: 'en' } }),
}));
jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn(), info: jest.fn() },
}));
jest.mock('../../services/authService', () => ({
  authService: {
    login: jest.fn(),
    register: jest.fn(),
    verifyAccount: jest.fn(),
    requestPasswordReset: jest.fn(),
    resetPassword: jest.fn(),
    resendVerificationCode: jest.fn(),
  },
}));
jest.mock('../../context/AuthContext', () => ({
  useAuth: jest.fn(),
}));
jest.mock('../../components/DemoBanner', () => () => <div>DemoBanner</div>);
jest.mock('../../components/LanguageSwitcher', () => () => <div>LanguageSwitcher</div>);
jest.mock('../../components/SafeReCAPTCHA', () => () => <div>SafeReCAPTCHA</div>);

const mockNavigate = jest.fn();
jest.mock('react-router-dom', () => ({
  useNavigate: () => mockNavigate,
}));
const navigate = mockNavigate;

describe('HomePage', () => {
  const login = jest.fn();
  const logout = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
    useAuth.mockReturnValue({ token: null, isLoading: false, login, logout });
    localStorage.clear();
  });

  test('with no session, redirects nowhere and shows the sign-in tab by default', () => {
    render(<HomePage />);
    expect(navigate).not.toHaveBeenCalled();
    expect(screen.getByPlaceholderText('Email')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Password')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('Username')).not.toBeInTheDocument();
  });

  test('with a live token, redirects straight to the board', () => {
    useAuth.mockReturnValue({ token: 'jwt', isLoading: false, login, logout });
    render(<HomePage />);
    expect(navigate).toHaveBeenCalledWith('/board');
  });

  test('with an expired token, signs out instead of redirecting', () => {
    useAuth.mockReturnValue({ token: 'jwt', isLoading: false, login, logout });
    localStorage.setItem('tokenExpiration', String(Date.now() - 1000));
    render(<HomePage />);
    expect(logout).toHaveBeenCalled();
    expect(navigate).not.toHaveBeenCalledWith('/board');
  });

  test('switching to the register tab shows the registration fields', () => {
    render(<HomePage />);
    fireEvent.click(screen.getByText('Register'));
    expect(screen.getByPlaceholderText('Username')).toBeInTheDocument();
    expect(screen.getByPlaceholderText('Confirm Password')).toBeInTheDocument();
  });

  test('the password field is masked until the eye toggle is clicked', () => {
    render(<HomePage />);
    const passwordInput = screen.getByPlaceholderText('Password');
    expect(passwordInput).toHaveAttribute('type', 'password');

    fireEvent.click(screen.getByLabelText('Show password'));

    expect(passwordInput).toHaveAttribute('type', 'text');
  });

  test('a successful login stores the session and goes to the board', async () => {
    authService.login.mockResolvedValue({ token: 'jwt', refreshToken: 'rt', expiresIn: 900000 });
    render(<HomePage />);

    fireEvent.change(screen.getByPlaceholderText('Email'), { target: { value: 'ada@example.com' } });
    fireEvent.change(screen.getByPlaceholderText('Password'), { target: { value: 'hunter22' } });
    fireEvent.click(screen.getByText('Sign In'));

    await waitFor(() => expect(authService.login).toHaveBeenCalledWith({ email: 'ada@example.com', password: 'hunter22' }));
    expect(login).toHaveBeenCalledWith({ token: 'jwt', refreshToken: 'rt', expiresIn: 900000 });
    expect(toast.success).toHaveBeenCalled();
    expect(navigate).toHaveBeenCalledWith('/board');
  });

  test('a refused login shows the error and does not sign in', async () => {
    authService.login.mockRejectedValue(new Error('Bad credentials'));
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
    render(<HomePage />);

    fireEvent.change(screen.getByPlaceholderText('Email'), { target: { value: 'ada@example.com' } });
    fireEvent.change(screen.getByPlaceholderText('Password'), { target: { value: 'wrong' } });
    fireEvent.click(screen.getByText('Sign In'));

    await waitFor(() => expect(screen.getByText('Bad credentials')).toBeInTheDocument());
    expect(login).not.toHaveBeenCalled();
    expect(toast.error).toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  test('registering with mismatched passwords never calls the server', async () => {
    render(<HomePage />);
    fireEvent.click(screen.getByText('Register'));

    fireEvent.change(screen.getByPlaceholderText('Username'), { target: { value: 'ada' } });
    fireEvent.change(screen.getByPlaceholderText('Email'), { target: { value: 'ada@example.com' } });
    fireEvent.change(screen.getByPlaceholderText('Password'), { target: { value: 'hunter22' } });
    fireEvent.change(screen.getByPlaceholderText('Confirm Password'), { target: { value: 'different' } });
    fireEvent.click(screen.getByText('Register', { selector: '.auth-button' }));

    await waitFor(() => expect(screen.getByText('Passwords do not match')).toBeInTheDocument());
    expect(authService.register).not.toHaveBeenCalled();
  });

  test('a successful registration shows the verification form', async () => {
    authService.register.mockResolvedValue({});
    render(<HomePage />);
    fireEvent.click(screen.getByText('Register'));

    fireEvent.change(screen.getByPlaceholderText('Username'), { target: { value: 'ada' } });
    fireEvent.change(screen.getByPlaceholderText('Email'), { target: { value: 'ada@example.com' } });
    fireEvent.change(screen.getByPlaceholderText('Password'), { target: { value: 'hunter22' } });
    fireEvent.change(screen.getByPlaceholderText('Confirm Password'), { target: { value: 'hunter22' } });
    fireEvent.click(screen.getByText('Register', { selector: '.auth-button' }));

    await waitFor(() => expect(screen.getByText('Verify Your Account')).toBeInTheDocument());
    expect(authService.register).toHaveBeenCalledWith({
      username: 'ada', email: 'ada@example.com', password: 'hunter22', locale: 'en',
    });
  });

  const registerThenReachVerification = async () => {
    authService.register.mockResolvedValue({});
    render(<HomePage />);
    fireEvent.click(screen.getByText('Register'));
    fireEvent.change(screen.getByPlaceholderText('Username'), { target: { value: 'ada' } });
    fireEvent.change(screen.getByPlaceholderText('Email'), { target: { value: 'ada@example.com' } });
    fireEvent.change(screen.getByPlaceholderText('Password'), { target: { value: 'hunter22' } });
    fireEvent.change(screen.getByPlaceholderText('Confirm Password'), { target: { value: 'hunter22' } });
    fireEvent.click(screen.getByText('Register', { selector: '.auth-button' }));
    await waitFor(() => expect(screen.getByText('Verify Your Account')).toBeInTheDocument());
  };

  test('verifying with no session in the response returns to sign-in with the email prefilled', async () => {
    await registerThenReachVerification();
    authService.verifyAccount.mockResolvedValue({});

    fireEvent.change(screen.getByPlaceholderText('Verification Code'), { target: { value: '123456' } });
    fireEvent.click(screen.getByText('Verify Account'));

    await waitFor(() => expect(screen.getByPlaceholderText('Email')).toHaveValue('ada@example.com'));
    expect(login).not.toHaveBeenCalled();
  });

  test('verifying with a session in the response signs in directly', async () => {
    await registerThenReachVerification();
    authService.verifyAccount.mockResolvedValue({ token: 'jwt', refreshToken: 'rt', expiresIn: 900000 });

    fireEvent.change(screen.getByPlaceholderText('Verification Code'), { target: { value: '123456' } });
    fireEvent.click(screen.getByText('Verify Account'));

    await waitFor(() => expect(login).toHaveBeenCalledWith({ token: 'jwt', refreshToken: 'rt', expiresIn: 900000 }));
    expect(navigate).toHaveBeenCalledWith('/board');
  });

  test('resending the verification code calls the server', async () => {
    await registerThenReachVerification();
    authService.resendVerificationCode.mockResolvedValue({});

    fireEvent.click(screen.getByText('Resend Code'));

    await waitFor(() => expect(authService.resendVerificationCode).toHaveBeenCalledWith('ada@example.com'));
    expect(toast.info).toHaveBeenCalled();
  });

  test('the forgot-password link opens the reset request form', () => {
    render(<HomePage />);
    fireEvent.click(screen.getByText('Forgot your password?'));
    expect(screen.getByText('Reset Your Password')).toBeInTheDocument();
    expect(screen.getByText('Send Reset Code')).toBeInTheDocument();
  });

  test('requesting a reset code answers the same regardless of whether the address exists, then moves to confirm', async () => {
    authService.requestPasswordReset.mockResolvedValue({});
    render(<HomePage />);
    fireEvent.click(screen.getByText('Forgot your password?'));

    fireEvent.change(screen.getByPlaceholderText('Email'), { target: { value: 'nobody@example.com' } });
    fireEvent.click(screen.getByText('Send Reset Code'));

    await waitFor(() => expect(screen.getByText('Set New Password')).toBeInTheDocument());
    expect(authService.requestPasswordReset).toHaveBeenCalledWith('nobody@example.com');
    expect(toast.info).toHaveBeenCalled();
  });

  test('setting a new password from the reset code returns to sign-in with the email prefilled', async () => {
    authService.requestPasswordReset.mockResolvedValue({});
    authService.resetPassword.mockResolvedValue({});
    render(<HomePage />);
    fireEvent.click(screen.getByText('Forgot your password?'));
    fireEvent.change(screen.getByPlaceholderText('Email'), { target: { value: 'ada@example.com' } });
    fireEvent.click(screen.getByText('Send Reset Code'));
    await waitFor(() => expect(screen.getByText('Set New Password')).toBeInTheDocument());

    fireEvent.change(screen.getByPlaceholderText('Reset Code'), { target: { value: '654321' } });
    fireEvent.change(screen.getByPlaceholderText('New Password'), { target: { value: 'newHunter22' } });
    fireEvent.click(screen.getByText('Set New Password'));

    await waitFor(() => expect(authService.resetPassword).toHaveBeenCalledWith('ada@example.com', '654321', 'newHunter22'));
    await waitFor(() => expect(screen.getByPlaceholderText('Email')).toHaveValue('ada@example.com'));
    expect(screen.queryByText('Reset Your Password')).not.toBeInTheDocument();
  });

  test('closing the reset form returns to sign-in without submitting anything', () => {
    render(<HomePage />);
    fireEvent.click(screen.getByText('Forgot your password?'));

    fireEvent.click(screen.getByText('Back to sign in'));

    expect(screen.getByText('Sign In')).toBeInTheDocument();
    expect(authService.requestPasswordReset).not.toHaveBeenCalled();
  });
});
