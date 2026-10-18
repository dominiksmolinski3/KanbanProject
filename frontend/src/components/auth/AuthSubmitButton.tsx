import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';

const LONG_LOADING_MS = 10000;

const SPINNER_STYLE = { width: '20px', height: '20px', borderWidth: '2px' };

interface AuthSubmitButtonProps {
  loading: boolean;
  label: string;
  loadingLabel: string;
  disabled?: boolean;
  form?: string;
  patient?: boolean;
}

export default function AuthSubmitButton({ loading, label, loadingLabel, disabled = false, form, patient = false }: AuthSubmitButtonProps) {
  const { t } = useTranslation();
  const [longLoading, setLongLoading] = useState(false);

  useEffect(() => {
    if (!loading) {
      setLongLoading(false);
      return undefined;
    }
    const timeoutId = setTimeout(() => setLongLoading(true), LONG_LOADING_MS);
    return () => clearTimeout(timeoutId);
  }, [loading]);

  return (
    <>
      <button type="submit" form={form} disabled={loading || disabled} className="auth-button">
        {loading ? (
          <>
            <span className="loading-spinner" style={SPINNER_STYLE}></span>
            <span className="loading-text">{loadingLabel}</span>
          </>
        ) : label}
      </button>
      {patient && loading && longLoading && (
        <div className="long-loading-message">
          {t('auth.pleaseWait', 'Please wait! We are working on handling this.')}
        </div>
      )}
    </>
  );
}
