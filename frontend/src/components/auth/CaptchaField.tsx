import { useTranslation } from 'react-i18next';
import SafeReCAPTCHA from '../SafeReCAPTCHA';
import { recaptchaLanguage } from '../../services/recaptchaLoader';
import { useDocumentTheme } from '../../theme/useDocumentTheme';
import type { Captcha } from './useCaptcha';

const SPINNER_BOX_STYLE = {
  position: 'absolute',
  top: '39px',
  left: '151px',
  transform: 'translate(-50%, -50%)',
  zIndex: 1,
  width: '20px',
  height: '20px'
} as const;

export default function CaptchaField({ captcha }: { captcha: Captcha }) {
  const { t, i18n } = useTranslation();
  const documentTheme = useDocumentTheme();

  if (!captcha.required) return null;

  return (
    <div className="form-group recaptcha-wrapper" style={{ minHeight: '78px' }}>
      <div className="recaptcha-inner" style={{
        minHeight: '78px',
        maxHeight: '78px',
        position: 'relative',
        width: '302px',
        margin: '0 auto',
        overflow: 'hidden'
      }}>
        {!captcha.ready && (
          <div style={SPINNER_BOX_STYLE}>
            <span className="loading-spinner" style={{
              width: '20px',
              height: '20px',
              borderWidth: '2px',
              display: 'block'
            }}></span>
          </div>
        )}
        <SafeReCAPTCHA
          key={captcha.instance}
          ref={captcha.widgetRef}
          sitekey={captcha.siteKey}
          theme={documentTheme}
          hl={recaptchaLanguage(i18n)}
          onChange={(val: string | null) => captcha.setToken(val || '')}
          onReady={() => captcha.setReady(true)}
          onLoadError={() => {
            captcha.setLoadError(true);
            captcha.setWarn(true);
          }}
          onErrored={() => {
            captcha.setWarn(true);
            console.warn('[Captcha] onErrored event fired');
          }}
          style={{ opacity: captcha.ready ? 1 : 0, transition: 'opacity 0.3s ease' }}
        />
      </div>
      {captcha.warn && captcha.ready && (
        <div className="captcha-warning">
          {t('auth.captchaBlocked')}
        </div>
      )}
      {captcha.loadError && (
        <div className="captcha-load-error">
          {t('auth.captchaLoadError')} <button type="button" className="captcha-retry" onClick={captcha.retry}>{t('auth.retry')}</button>
        </div>
      )}
    </div>
  );
}
