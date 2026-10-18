import { useFormContext } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import AuthSubmitButton from './AuthSubmitButton';
import FormField from './FormField';
import type { AuthFields } from './authFields';
import type { AuthFlow } from './useAuthFlow';

export default function ResetForms({ flow }: { flow: AuthFlow }) {
  const { handleSubmit } = useFormContext<AuthFields>();
  const { t } = useTranslation();

  return (
    <div className="verification-form">
      {flow.resetStage === 'request' ? (
        <>
          <h2>{t('auth.resetPassword', 'Reset Your Password')}</h2>
          <p>{t('auth.resetIntro', 'Enter your email address and we will send you a reset code.')}</p>

          <form onSubmit={handleSubmit(flow.submitResetRequest)}>
            <FormField
              name="resetEmail"
              type="email"
              placeholder={t('auth.email', 'Email')}
            />

            {flow.error && <div className="error-message">{flow.error}</div>}

            <AuthSubmitButton
              loading={flow.loading}
              label={t('auth.sendResetCode', 'Send Reset Code')}
              loadingLabel={t('auth.sending', 'Sending...')}
            />

            <p className="resend-link">
              <span onClick={flow.closePasswordReset}>{t('auth.backToLogin', 'Back to sign in')}</span>
            </p>
          </form>
        </>
      ) : (
        <>
          <h2>{t('auth.resetPassword', 'Reset Your Password')}</h2>
          <p>{t('auth.enterResetCode', 'Enter the reset code sent to your email and choose a new password.')}</p>

          <form onSubmit={handleSubmit(flow.submitResetPassword)}>
            <FormField
              name="resetCode"
              type="text"
              inputMode="numeric"
              placeholder={t('auth.resetCode', 'Reset Code')}
            />
            <FormField
              name="newPassword"
              type="password"
              placeholder={t('auth.newPassword', 'New Password')}
              minLength={8}
              maxLength={72}
            />

            {flow.error && <div className="error-message">{flow.error}</div>}

            <AuthSubmitButton
              loading={flow.loading}
              label={t('auth.setNewPassword', 'Set New Password')}
              loadingLabel={t('auth.resetting', 'Resetting...')}
            />

            <p className="resend-link">
              <span onClick={() => flow.setResetStage('request')}>{t('auth.resendCode', 'Resend Code')}</span>
            </p>
            <p className="resend-link">
              <span onClick={flow.closePasswordReset}>{t('auth.backToLogin', 'Back to sign in')}</span>
            </p>
          </form>
        </>
      )}
    </div>
  );
}
