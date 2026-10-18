import { useFormContext } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import FormField from './FormField';
import PasswordField from './PasswordField';
import type { AuthFields } from './authFields';
import type { AuthFlow } from './useAuthFlow';

export const AUTH_FORM_ID = 'auth-form';

export default function LoginForm({ flow }: { flow: AuthFlow }) {
  const { handleSubmit } = useFormContext<AuthFields>();
  const { t } = useTranslation();

  return (
    <form id={AUTH_FORM_ID} onSubmit={handleSubmit(flow.submitLogin)}>
      <FormField
        id="login-email"
        name="email"
        type="email"
        label={t('auth.email')}
        placeholder={t('auth.email', 'Email')}
      />
      <PasswordField
        id="login-password"
        name="password"
        label={t('auth.password')}
        placeholder={t('auth.password', 'Password')}
        visible={flow.showPassword}
        onToggle={flow.togglePassword}
      />
      {flow.error && <div className="error-message">{flow.error}</div>}
      <p className="resend-link">
        <span onClick={flow.openPasswordReset}>
          {t('auth.forgotPassword', 'Forgot your password?')}
        </span>
      </p>
    </form>
  );
}
