import React, { useState, useEffect, useRef } from 'react';
import ReCAPTCHA from 'react-google-recaptcha';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { authService } from '../services/authService';
import { useAuth } from '../context/AuthContext';
import { useTranslation } from 'react-i18next';
import LanguageSwitcher from './LanguageSwitcher';
import '../styles/HomePage.css';

const HomePage = () => {
  const [activeTab, setActiveTab] = useState('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [username, setUsername] = useState('');
  const [verificationEmail, setVerificationEmail] = useState('');
  const [verificationCode, setVerificationCode] = useState('');
  const [showVerification, setShowVerification] = useState(false);
  // 'request' asks for a code, 'confirm' redeems it. null means the reset flow is not showing.
  const [resetStage, setResetStage] = useState(null);
  const [resetEmail, setResetEmail] = useState('');
  const [resetCode, setResetCode] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [longLoading, setLongLoading] = useState(false);
  const [captchaToken, setCaptchaToken] = useState('');
  const isCaptchaRequired = !!import.meta.env.VITE_RECAPTCHA_SITE_KEY;
  const [captchaReady, setCaptchaReady] = useState(false);
  const [captchaLoadError, setCaptchaLoadError] = useState(false);
  const siteKey = import.meta.env.VITE_RECAPTCHA_SITE_KEY;
  const recaptchaRef = useRef(null);
  const [captchaKey, setCaptchaKey] = useState(0);
  const [captchaWarn, setCaptchaWarn] = useState(false);

  const { t } = useTranslation();
  const { login, logout } = useAuth();
  const navigate = useNavigate();

  const { token, isLoading } = useAuth();

  useEffect(() => {
    const validateToken = async () => {
      if (token && !isLoading) {
        const expiration = localStorage.getItem('tokenExpiration');
        if (expiration && new Date().getTime() > parseInt(expiration)) {
          logout();
          toast.info(t('auth.sessionExpired', 'Your session has expired. Please sign in again.'));
          return;
        }
        
        navigate('/board');
      }
    };
    
    validateToken();
  }, [token, isLoading, navigate, logout, t]);

  useEffect(() => {
    let timeoutId;
    
    if (loading) {
      timeoutId = setTimeout(() => {
        setLongLoading(true);
      }, 10000);
    } else {
      setLongLoading(false);
    }
    
    return () => {
      clearTimeout(timeoutId);
    };
  }, [loading]);
  
  const handleLogin = async (e) => {
    if (e && e.preventDefault) e.preventDefault();
    setError('');
    setLoading(true);
    
    try {
  const payload = { email, password };
  if (isCaptchaRequired && captchaToken) payload.captcha = { token: captchaToken };
  const response = await authService.login(payload);
      login(response.token, response.expiresIn);
      toast.success(t('auth.loginSuccess', 'Successfully signed in!'));
      navigate('/board');
    } catch (error) {
      console.error('[Auth] Login failed:', error.message);
      setError(error.message);
      toast.error(t('auth.loginFailed', 'Login failed:') + ' ' + error.message);
    } finally {
      setLoading(false);
    }
  };
  
  const handleRegister = async (e) => {
    if (e && e.preventDefault) e.preventDefault();
    setError('');
    setLoading(true);
    
    try {
  const payload = { username, email, password };
  if (isCaptchaRequired && captchaToken) payload.captcha = { token: captchaToken };
  await authService.register(payload);
      toast.success(t('auth.registerSuccess', 'If that address is new here, a verification code is on its way to it.'));
      setShowVerification(true);
      setVerificationEmail(email);
    } catch (error) {
      setError(error.message);
      toast.error(t('auth.registerFailed', 'Registration failed:') + ' ' + error.message);
    } finally {
      setLoading(false);
    }
  };
  
  const handleVerify = async (e) => {
    if (e && e.preventDefault) e.preventDefault();
    setError('');
    setLoading(true);
    
    try {
      const response = await authService.verifyAccount({ email: verificationEmail, verificationCode });
      
      if (response && response.token) {
        login(response.token, response.expiresIn);
        toast.success(t('auth.verifyAndLoginSuccess', 'Account verified and signed in successfully!'));
        navigate('/board');
      } else {
        toast.success(t('auth.verifySuccess', 'Account verified successfully! Please sign in.'));
        setActiveTab('login');
        setEmail(verificationEmail);
      }
    } catch (error) {
      setError(error.message);
      toast.error(t('auth.verifyFailed', 'Verification failed:') + ' ' + error.message);
    } finally {
      setLoading(false);
    }
  };
  
  const openPasswordReset = () => {
    setError('');
    setResetEmail(email);
    setResetCode('');
    setNewPassword('');
    setResetStage('request');
  };

  const closePasswordReset = () => {
    setError('');
    setResetStage(null);
  };

  const handleRequestReset = async (e) => {
    if (e && e.preventDefault) e.preventDefault();
    setError('');
    setLoading(true);

    try {
      await authService.requestPasswordReset(resetEmail);
      // The server answers the same either way, so the message says what was accepted rather
      // than what happened - claiming a code was sent would be a membership oracle in the UI.
      toast.info(t('auth.resetRequested', 'If that address has an account, a reset code is on its way.'));
      setResetStage('confirm');
    } catch (error) {
      setError(error.message);
      toast.error(t('auth.resetRequestFailed', 'Could not request a reset:') + ' ' + error.message);
    } finally {
      setLoading(false);
    }
  };

  const handleResetPassword = async (e) => {
    if (e && e.preventDefault) e.preventDefault();
    setError('');
    setLoading(true);

    try {
      await authService.resetPassword(resetEmail, resetCode, newPassword);
      toast.success(t('auth.resetSuccess', 'Password changed. You can sign in with it now.'));
      setResetStage(null);
      setActiveTab('login');
      setEmail(resetEmail);
      setPassword('');
    } catch (error) {
      setError(error.message);
      toast.error(t('auth.resetFailed', 'Could not reset the password:') + ' ' + error.message);
    } finally {
      setLoading(false);
    }
  };

  const handleResendCode = async () => {
    try {
      await authService.resendVerificationCode(verificationEmail);
      toast.info(t('auth.codeSent', 'If that address has an account waiting to be verified, a new code is on its way.'));
    } catch (error) {
      setError(error.message);
    }
  };
  
  return (
    <div className="home-container">
      <div className="language-switcher-container">
        <LanguageSwitcher />
      </div>
      <div className="auth-container">
        <h1>{t('board.title', 'Kanban Project')}</h1>
        
        {resetStage ? (
          <div className="verification-form">
            {resetStage === 'request' ? (
              <>
                <h2>{t('auth.resetPassword', 'Reset Your Password')}</h2>
                <p>{t('auth.resetIntro', 'Enter your email address and we will send you a reset code.')}</p>

                <form onSubmit={handleRequestReset}>
                  <div className="form-group">
                    <input
                      type="email"
                      placeholder={t('auth.email', 'Email')}
                      value={resetEmail}
                      onChange={(e) => setResetEmail(e.target.value)}
                      required
                    />
                  </div>

                  {error && <div className="error-message">{error}</div>}

                  <button type="submit" disabled={loading} className="auth-button">
                    {loading ? (
                      <>
                        <span className="loading-spinner" style={{ width: '20px', height: '20px', borderWidth: '2px' }}></span>
                        <span className="loading-text">{t('auth.sending', 'Sending...')}</span>
                      </>
                    ) : t('auth.sendResetCode', 'Send Reset Code')}
                  </button>

                  <p className="resend-link">
                    <span onClick={closePasswordReset}>{t('auth.backToLogin', 'Back to sign in')}</span>
                  </p>
                </form>
              </>
            ) : (
              <>
                <h2>{t('auth.resetPassword', 'Reset Your Password')}</h2>
                <p>{t('auth.enterResetCode', 'Enter the reset code sent to your email and choose a new password.')}</p>

                <form onSubmit={handleResetPassword}>
                  <div className="form-group">
                    <input
                      type="text"
                      inputMode="numeric"
                      placeholder={t('auth.resetCode', 'Reset Code')}
                      value={resetCode}
                      onChange={(e) => setResetCode(e.target.value)}
                      required
                    />
                  </div>
                  <div className="form-group">
                    <input
                      type="password"
                      placeholder={t('auth.newPassword', 'New Password')}
                      value={newPassword}
                      onChange={(e) => setNewPassword(e.target.value)}
                      minLength={8}
                      maxLength={72}
                      required
                    />
                  </div>

                  {error && <div className="error-message">{error}</div>}

                  <button type="submit" disabled={loading} className="auth-button">
                    {loading ? (
                      <>
                        <span className="loading-spinner" style={{ width: '20px', height: '20px', borderWidth: '2px' }}></span>
                        <span className="loading-text">{t('auth.resetting', 'Resetting...')}</span>
                      </>
                    ) : t('auth.setNewPassword', 'Set New Password')}
                  </button>

                  <p className="resend-link">
                    <span onClick={() => setResetStage('request')}>{t('auth.resendCode', 'Resend Code')}</span>
                  </p>
                  <p className="resend-link">
                    <span onClick={closePasswordReset}>{t('auth.backToLogin', 'Back to sign in')}</span>
                  </p>
                </form>
              </>
            )}
          </div>
        ) : showVerification ? (
          <div className="verification-form">
            <h2>{t('auth.verifyAccount', 'Verify Your Account')}</h2>
            <p>{t('auth.enterCode', 'Enter the verification code sent to your email')}</p>
            
            <form onSubmit={handleVerify}>
              <div className="form-group">
                <input
                  type="Verification Code"
                  placeholder={t('auth.verificationCode', 'Verification Code')}
                  value={verificationCode}
                  onChange={(e) => setVerificationCode(e.target.value)}
                  required
                />
              </div>
              
              {error && <div className="error-message">{error}</div>}
              
              <button type="submit" disabled={loading} className="auth-button">
              {loading ? (
                  <>
                  <span className="loading-spinner" style={{ width: '20px', height: '20px', borderWidth: '2px' }}></span>
                  <span className="loading-text">{t('auth.verifying', 'Verifying...')}</span>
                  </>
                ) : t('auth.verifyAccount', 'Verify Account')}
              </button>
              {loading && longLoading && (  
                <div className="long-loading-message">
                  {t('auth.pleaseWait', 'Please wait! We are working on handling this.')}
                </div>
              )}
              
              <p className="resend-link">
                {t('auth.noCode', 'Didn\'t receive a code?')}{' '}
                <span onClick={handleResendCode}>{t('auth.resendCode', 'Resend Code')}</span>
              </p>
            </form>
          </div>
        ) : (
          <>
            <div className="tabs">
              <button
                className={activeTab === 'login' ? 'active' : ''}
                onClick={() => setActiveTab('login')}
              >
                {t('auth.login', 'Login')}
              </button>
              <button
                className={activeTab === 'register' ? 'active' : ''}
                onClick={() => setActiveTab('register')}
              >
                {t('auth.register', 'Register')}
              </button>
            </div>
            <div className="tab-content">
              {activeTab === 'login' ? (
                <form onSubmit={handleLogin}>
                  <div className="form-group">
                    <input
                      type="email"
                      placeholder={t('auth.email', 'Email')}
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      required
                    />
                  </div>
                  <div className="form-group">
                    <input
                      type="password"
                      placeholder={t('auth.password', 'Password')}
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      required
                    />
                  </div>
                  {error && <div className="error-message">{error}</div>}
                  <p className="resend-link">
                    <span onClick={openPasswordReset}>
                      {t('auth.forgotPassword', 'Forgot your password?')}
                    </span>
                  </p>
                </form>
              ) : (
                <form onSubmit={handleRegister}>
                  <div className="form-group">
                    <input
                      type="username"
                      placeholder={t('auth.username', 'Username')}
                      value={username}
                      onChange={(e) => setUsername(e.target.value)}
                      required
                    />
                  </div>
                  <div className="form-group">
                    <input
                      type="email"
                      placeholder={t('auth.email', 'Email')}
                      value={email}
                      onChange={(e) => setEmail(e.target.value)}
                      required
                    />
                  </div>
                  <div className="form-group">
                    <input
                      type="password"
                      placeholder={t('auth.password', 'Password')}
                      value={password}
                      onChange={(e) => setPassword(e.target.value)}
                      required
                    />
                  </div>
                  {error && <div className="error-message">{error}</div>}
                </form>
              )}
            </div>

            {isCaptchaRequired && (
              <div className="form-group recaptcha-wrapper" style={{ minHeight: '78px' }}>
                <div className="recaptcha-inner" style={{ 
                  minHeight: '78px', 
                  maxHeight: '78px',
                  position: 'relative', 
                  width: '302px', 
                  margin: '0 auto',
                  overflow: 'hidden'
                }}>
                  {!captchaReady && (
                    <div style={{ 
                      position: 'absolute', 
                      top: '39px', 
                      left: '151px', 
                      transform: 'translate(-50%, -50%)',
                      zIndex: 1,
                      width: '20px',
                      height: '20px'
                    }}>
                      <span className="loading-spinner" style={{ 
                        width: '20px', 
                        height: '20px', 
                        borderWidth: '2px',
                        display: 'block'
                      }}></span>
                    </div>
                  )}
                  <ReCAPTCHA
                    key={captchaKey}
                    ref={recaptchaRef}
                    sitekey={siteKey}
                    onChange={(val) => setCaptchaToken(val || '')}
                    asyncScriptOnLoad={() => setCaptchaReady(true)}
                    onErrored={() => {
                      setCaptchaWarn(true);
                      console.warn('[Captcha] onErrored event fired');
                    }}
                    style={{ opacity: captchaReady ? 1 : 0, transition: 'opacity 0.3s ease' }}
                  />
                  {/* Manual fallback container (will be used only if library fails to inject iframe) */}
                  {!captchaReady && (
                    <div id="manual-recaptcha" style={{ minWidth: 302, minHeight: 76 }} />
                  )}
                </div>
                {captchaWarn && captchaReady && (
                  <div className="captcha-warning" style={{ marginTop: '8px', fontSize: '0.8rem', color: '#c0392b' }}>
                    {t('auth.captchaBlocked')}
                  </div>
                )}
                {captchaLoadError && (
                  <div style={{ marginTop: '6px', fontSize: '0.75rem', color: '#b03a2e' }}>
                    {t('auth.captchaLoadError')} <button type="button" style={{ textDecoration: 'underline', background: 'none', border: 'none', cursor: 'pointer', color: '#007bff', padding: 0 }} onClick={() => {
                      setCaptchaLoadError(false);
                      setCaptchaWarn(false);
                      setCaptchaReady(false);
                      setCaptchaKey(k => k + 1);
                      // Try reinjecting if still absent
                      const existing = document.querySelector('script[src*="recaptcha/api.js"]');
                      if (!existing) {
                        const script = document.createElement('script');
                        script.src = 'https://www.google.com/recaptcha/api.js?render=explicit';
                        script.async = true;
                        script.defer = true;
                        script.onerror = () => {
                          setCaptchaLoadError(true);
                          setCaptchaWarn(true);
                        };
                        document.head.appendChild(script);
                      }
                    }}>{t('auth.retry')}</button>
                  </div>
                )}
              </div>
            )}

            <div className="shared-submit">
              <button
                type="button"
                disabled={loading || (isCaptchaRequired && !captchaToken)}
                className="auth-button"
                onClick={() => {
                  if (activeTab === 'login') handleLogin();
                  else handleRegister();
                }}
              >
                {loading ? (
                  <>
                    <span className="loading-spinner" style={{ width: '20px', height: '20px', borderWidth: '2px' }}></span>
                    <span className="loading-text">{activeTab === 'login' ? t('auth.signingIn', 'Signing In...') : t('auth.registering', 'Registering...')}</span>
                  </>
                ) : (activeTab === 'login' ? t('auth.signIn', 'Sign In') : t('auth.register', 'Register'))}
              </button>
              {loading && longLoading && (
                <div className="long-loading-message">
                  {t('auth.pleaseWait', 'Please wait! We are working on handling this.')}
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </div>
  );
};

export default HomePage;