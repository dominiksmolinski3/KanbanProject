import { useEffect } from 'react';
import { FormProvider, useForm } from 'react-hook-form';
import { useNavigate } from 'react-router-dom';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../context/AuthContext';
import LanguageSwitcher from './LanguageSwitcher';
import DemoBanner from './DemoBanner';
import AuthBrand from './auth/AuthBrand';
import AuthSubmitButton from './auth/AuthSubmitButton';
import CaptchaField from './auth/CaptchaField';
import LoginForm, { AUTH_FORM_ID } from './auth/LoginForm';
import RegisterForm from './auth/RegisterForm';
import ResetForms from './auth/ResetForms';
import VerifyForm from './auth/VerifyForm';
import { AUTH_DEFAULTS, type AuthFields } from './auth/authFields';
import { useAuthFlow } from './auth/useAuthFlow';
import { useCaptcha } from './auth/useCaptcha';
import '../styles/HomePage.css';

const HomePage = () => {
  const { t } = useTranslation();
  const { token, isLoading, logout } = useAuth();
  const navigate = useNavigate();
  const form = useForm<AuthFields>({ defaultValues: AUTH_DEFAULTS });
  const captcha = useCaptcha();
  const flow = useAuthFlow(form, captcha);

  useEffect(() => {
    if (token && !isLoading) {
      const expiration = localStorage.getItem('tokenExpiration');
      if (expiration && new Date().getTime() > parseInt(expiration)) {
        logout();
        toast.info(t('auth.sessionExpired', 'Your session has expired. Please sign in again.'));
        return;
      }

      navigate('/board');
    }
  }, [token, isLoading, navigate, logout, t]);

  const away = Boolean(flow.resetStage || flow.showVerification);
  const { setReady } = captcha;
  useEffect(() => {
    if (away) setReady(false);
  }, [away, setReady]);

  const signingIn = flow.activeTab === 'login';

  return (
    <FormProvider {...form}>
      <div className="home-container">
        <DemoBanner />
        <div className="auth-layout">
          <AuthBrand />
          <main className="auth-main">
            <div className="auth-container">
              <div className="language-switcher-container">
                <LanguageSwitcher />
              </div>

              {flow.resetStage ? (
                <ResetForms flow={flow} />
              ) : flow.showVerification ? (
                <VerifyForm flow={flow} />
              ) : (
                <>
                  <div className="tabs" role="tablist">
                    <button
                      type="button"
                      role="tab"
                      aria-selected={signingIn}
                      className={signingIn ? 'active' : ''}
                      onClick={() => flow.setActiveTab('login')}
                    >
                      {t('auth.login', 'Login')}
                    </button>
                    <button
                      type="button"
                      role="tab"
                      aria-selected={!signingIn}
                      className={!signingIn ? 'active' : ''}
                      onClick={() => flow.setActiveTab('register')}
                    >
                      {t('auth.register', 'Register')}
                    </button>
                  </div>
                  <div className="tab-content">
                    {signingIn ? <LoginForm flow={flow} /> : <RegisterForm flow={flow} />}
                  </div>

                  <CaptchaField captcha={captcha} />

                  <div className="shared-submit">
                    <AuthSubmitButton
                      form={AUTH_FORM_ID}
                      loading={flow.loading}
                      disabled={captcha.required && !captcha.token}
                      label={signingIn ? t('auth.signIn', 'Sign In') : t('auth.register', 'Register')}
                      loadingLabel={signingIn ? t('auth.signingIn', 'Signing In...') : t('auth.registering', 'Registering...')}
                      patient
                    />
                  </div>
                </>
              )}
            </div>
          </main>
        </div>
      </div>
    </FormProvider>
  );
};

export default HomePage;
