import { useState } from 'react';
import type { UseFormReturn } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../../context/AuthContext';
import { messageOf } from '../../context/kanban/reportError';
import { authService, type Credentials, type SignupData } from '../../services/authService';
import type { LoginResponse } from '../../types/api';
import type { AuthFields } from './authFields';
import type { Captcha } from './useCaptcha';

export type AuthTab = 'login' | 'register';
export type ResetStage = 'request' | 'confirm' | null;

export function useAuthFlow({ getValues, setValue }: UseFormReturn<AuthFields>, captcha: Captcha) {
  const { t, i18n } = useTranslation();
  const { login } = useAuth();
  const navigate = useNavigate();

  const [activeTab, setActiveTab] = useState<AuthTab>('login');
  const [showPassword, setShowPassword] = useState(false);
  const [showVerification, setShowVerification] = useState(false);
  const [verificationEmail, setVerificationEmail] = useState('');
  const [resetStage, setResetStage] = useState<ResetStage>(null);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  const withCaptcha = <T extends Credentials>(payload: T): T =>
    captcha.required && captcha.token ? { ...payload, captcha: { token: captcha.token } } : payload;

  const submitLogin = async () => {
    const { email, password } = getValues();
    setError('');
    setLoading(true);

    try {
      const response = await authService.login(withCaptcha({ email, password }));
      login(response);
      toast.success(t('auth.loginSuccess', 'Successfully signed in!'));
      navigate('/board');
    } catch (error) {
      console.error('[Auth] Login failed:', messageOf(error));
      setError(messageOf(error));
      toast.error(t('auth.loginFailed', 'Login failed:') + ' ' + messageOf(error));
      captcha.reset();
    } finally {
      setLoading(false);
    }
  };

  const submitRegister = async () => {
    const { username, email, password, confirmPassword } = getValues();
    setError('');

    if (password !== confirmPassword) {
      const message = t('auth.passwordsMismatch', 'Passwords do not match');
      setError(message);
      toast.error(message);
      return;
    }

    setLoading(true);

    try {
      const payload: SignupData = withCaptcha({ username, email, password, locale: (i18n.language || 'en').split('-')[0] });
      await authService.register(payload);
      toast.success(t('auth.registerSuccess', 'If that address is new here, a verification code is on its way to it.'));
      setShowVerification(true);
      setVerificationEmail(email);
    } catch (error) {
      setError(messageOf(error));
      toast.error(t('auth.registerFailed', 'Registration failed:') + ' ' + messageOf(error));
      captcha.reset();
    } finally {
      setLoading(false);
    }
  };

  const submitVerify = async () => {
    const { verificationCode, password } = getValues();
    setError('');
    setLoading(true);

    try {
      const response = await authService.verifyAccount({ email: verificationEmail, verificationCode, password }) as Partial<LoginResponse> | string | null;

      if (response && typeof response === 'object' && response.token) {
        login(response as LoginResponse);
        toast.success(t('auth.verifyAndLoginSuccess', 'Account verified and signed in successfully!'));
        navigate('/board');
      } else {
        toast.success(t('auth.verifySuccess', 'Account verified successfully! Please sign in.'));
        setShowVerification(false);
        setValue('verificationCode', '');
        setActiveTab('login');
        setValue('email', verificationEmail);
      }
    } catch (error) {
      setError(messageOf(error));
      toast.error(t('auth.verifyFailed', 'Verification failed:') + ' ' + messageOf(error));
    } finally {
      setLoading(false);
    }
  };

  const openPasswordReset = () => {
    setError('');
    setValue('resetEmail', getValues('email'));
    setValue('resetCode', '');
    setValue('newPassword', '');
    setResetStage('request');
  };

  const closePasswordReset = () => {
    setError('');
    setResetStage(null);
  };

  const submitResetRequest = async () => {
    setError('');
    setLoading(true);

    try {
      await authService.requestPasswordReset(getValues('resetEmail'));
      toast.info(t('auth.resetRequested', 'If that address has an account, a reset code is on its way.'));
      setResetStage('confirm');
    } catch (error) {
      setError(messageOf(error));
      toast.error(t('auth.resetRequestFailed', 'Could not request a reset:') + ' ' + messageOf(error));
    } finally {
      setLoading(false);
    }
  };

  const submitResetPassword = async () => {
    const { resetEmail, resetCode, newPassword } = getValues();
    setError('');
    setLoading(true);

    try {
      await authService.resetPassword(resetEmail, resetCode, newPassword);
      toast.success(t('auth.resetSuccess', 'Password changed. You can sign in with it now.'));
      setResetStage(null);
      setActiveTab('login');
      setValue('email', resetEmail);
      setValue('password', '');
    } catch (error) {
      setError(messageOf(error));
      toast.error(t('auth.resetFailed', 'Could not reset the password:') + ' ' + messageOf(error));
    } finally {
      setLoading(false);
    }
  };

  const resendCode = async () => {
    try {
      await authService.resendVerificationCode(verificationEmail);
      toast.info(t('auth.codeSent', 'If that address has an account waiting to be verified, a new code is on its way.'));
    } catch (error) {
      setError(messageOf(error));
    }
  };

  return {
    activeTab, setActiveTab,
    showPassword, togglePassword: () => setShowPassword((visible) => !visible),
    showVerification, resetStage, setResetStage,
    error, loading,
    submitLogin, submitRegister, submitVerify,
    openPasswordReset, closePasswordReset, submitResetRequest, submitResetPassword, resendCode,
  };
}

export type AuthFlow = ReturnType<typeof useAuthFlow>;
