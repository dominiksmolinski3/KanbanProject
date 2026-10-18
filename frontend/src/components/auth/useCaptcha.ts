import { useRef, useState } from 'react';
import { resetRecaptchaLoader } from '../../services/recaptchaLoader';
import type { ReCaptchaHandle } from '../SafeReCAPTCHA';

export function useCaptcha() {
  const siteKey = import.meta.env.VITE_RECAPTCHA_SITE_KEY as string | undefined;
  const required = !!siteKey;
  const [token, setToken] = useState('');
  const [ready, setReady] = useState(false);
  const [loadError, setLoadError] = useState(false);
  const [warn, setWarn] = useState(false);
  const [instance, setInstance] = useState(0);
  const widgetRef = useRef<ReCaptchaHandle | null>(null);

  const reset = () => {
    if (!required) return;
    setToken('');
    try {
      widgetRef.current?.reset();
    } catch {
      // The widget was never mounted; clearing the token is enough.
    }
  };

  const retry = () => {
    setLoadError(false);
    setWarn(false);
    setReady(false);
    resetRecaptchaLoader();
    setInstance((k) => k + 1);
  };

  return { required, siteKey, token, ready, loadError, warn, instance, widgetRef, setToken, setReady, setLoadError, setWarn, reset, retry };
}

export type Captcha = ReturnType<typeof useCaptcha>;
