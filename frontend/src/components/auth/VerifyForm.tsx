import { useFormContext } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import AuthSubmitButton from './AuthSubmitButton';
import FormField from './FormField';
import type { AuthFields } from './authFields';
import type { AuthFlow } from './useAuthFlow';

export default function VerifyForm({ flow }: { flow: AuthFlow }) {
  const { handleSubmit } = useFormContext<AuthFields>();
  const { t } = useTranslation();

  return (
    <div className="verification-form">
      <h2>{t('auth.verifyAccount', 'Verify Your Account')}</h2>
      <p>{t('auth.enterCode', 'Enter the verification code sent to your email')}</p>

      <form onSubmit={handleSubmit(flow.submitVerify)}>
        <FormField
          name="verificationCode"
          type="text"
          placeholder={t('auth.verificationCode', 'Verification Code')}
        />

        {flow.error && <div className="error-message">{flow.error}</div>}

        <AuthSubmitButton
          loading={flow.loading}
          label={t('auth.verifyAccount', 'Verify Account')}
          loadingLabel={t('auth.verifying', 'Verifying...')}
          patient
        />

        <p className="resend-link">
          {t('auth.noCode', 'Didn\'t receive a code?')}{' '}
          <span onClick={flow.resendCode}>{t('auth.resendCode', 'Resend Code')}</span>
        </p>
      </form>
    </div>
  );
}
