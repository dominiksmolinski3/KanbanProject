import { useFormContext } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import FormField from './FormField';
import PasswordField from './PasswordField';
import { AUTH_FORM_ID } from './LoginForm';
import type { AuthFields } from './authFields';
import type { AuthFlow } from './useAuthFlow';

export default function RegisterForm({ flow }: { flow: AuthFlow }) {
  const { handleSubmit } = useFormContext<AuthFields>();
  const { t } = useTranslation();

  return (
    <form id={AUTH_FORM_ID} onSubmit={handleSubmit(flow.submitRegister)}>
      <FormField
        id="register-username"
        name="username"
        type="text"
        label={t('auth.username')}
        placeholder={t('auth.username', 'Username')}
      />
      <FormField
        id="register-email"
        name="email"
        type="email"
        label={t('auth.email')}
        placeholder={t('auth.email', 'Email')}
      />
      <PasswordField
        id="register-password"
        name="password"
        label={t('auth.password')}
        placeholder={t('auth.password', 'Password')}
        visible={flow.showPassword}
        onToggle={flow.togglePassword}
      />
      <PasswordField
        id="register-confirm"
        name="confirmPassword"
        label={t('auth.confirmPassword')}
        placeholder={t('auth.confirmPassword', 'Confirm Password')}
        visible={flow.showPassword}
      />
      {flow.error && <div className="error-message">{flow.error}</div>}
    </form>
  );
}
