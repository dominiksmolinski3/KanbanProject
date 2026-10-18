import { forwardRef, useEffect, useImperativeHandle, useRef, type HTMLAttributes } from 'react';
import { loadRecaptcha } from '../services/recaptchaLoader';

const WIDGET_PAINT_TIMEOUT_MS = 5000;

export interface ReCaptchaHandle {
  reset: () => void;
  getValue: () => string | null;
  getWidgetId: () => number | null;
}

interface SafeReCAPTCHAProps extends Omit<HTMLAttributes<HTMLDivElement>, 'onChange'> {
  sitekey?: string;
  theme?: 'light' | 'dark';
  size?: string;
  hl?: string;
  onChange?: (token: string | null) => void;
  onExpired?: () => void;
  onErrored?: () => void;
  onReady?: () => void;
  onLoadError?: (error: unknown) => void;
}

interface Handlers {
  onChange?: SafeReCAPTCHAProps['onChange'];
  onExpired?: SafeReCAPTCHAProps['onExpired'];
  onErrored?: SafeReCAPTCHAProps['onErrored'];
  onReady?: SafeReCAPTCHAProps['onReady'];
  onLoadError?: SafeReCAPTCHAProps['onLoadError'];
}

const SafeReCAPTCHA = forwardRef<ReCaptchaHandle, SafeReCAPTCHAProps>(function SafeReCAPTCHA(
  { sitekey, theme, size, hl, onChange, onExpired, onErrored, onReady, onLoadError, ...rest },
  ref,
) {
  const holderRef = useRef<HTMLDivElement>(null);
  const widgetIdRef = useRef<number | null>(null);

  const handlersRef = useRef<Handlers>({});
  handlersRef.current = { onChange, onExpired, onErrored, onReady, onLoadError };

  useImperativeHandle(
    ref,
    () => ({
      reset() {
        const grecaptcha = window.grecaptcha;
        if (grecaptcha && widgetIdRef.current !== null) grecaptcha.reset(widgetIdRef.current);
      },
      getValue() {
        const grecaptcha = window.grecaptcha;
        if (grecaptcha && widgetIdRef.current !== null) {
          return grecaptcha.getResponse(widgetIdRef.current);
        }
        return null;
      },
      getWidgetId() {
        return widgetIdRef.current;
      },
    }),
    [],
  );

  useEffect(() => {
    let cancelled = false;
    let stopWaitingForPaint: (() => void) | null = null;
    const holder = holderRef.current;

    const announceWhenPainted = (target: HTMLElement) => {
      const iframe = target.querySelector('iframe');
      if (!iframe) {
        handlersRef.current.onReady?.();
        return;
      }

      let announced = false;
      const announce = () => {
        if (announced || cancelled) return;
        announced = true;
        stopWaitingForPaint?.();
        handlersRef.current.onReady?.();
      };

      const timeoutId = setTimeout(announce, WIDGET_PAINT_TIMEOUT_MS);
      iframe.addEventListener('load', announce);
      stopWaitingForPaint = () => {
        clearTimeout(timeoutId);
        iframe.removeEventListener('load', announce);
      };
    };

    loadRecaptcha().then(
      (grecaptcha) => {
        if (cancelled || !holder || holder.firstChild) return;

        const target = document.createElement('div');
        holder.appendChild(target);
        widgetIdRef.current = grecaptcha.render(target, {
          sitekey,
          theme,
          size,
          hl,
          callback: (token: string) => handlersRef.current.onChange?.(token),
          'expired-callback': () =>
            handlersRef.current.onExpired
              ? handlersRef.current.onExpired()
              : handlersRef.current.onChange?.(null),
          'error-callback': () => handlersRef.current.onErrored?.(),
        });
        announceWhenPainted(target);
      },
      (error) => {
        if (!cancelled) handlersRef.current.onLoadError?.(error);
      },
    );

    return () => {
      cancelled = true;
      stopWaitingForPaint?.();
      while (holder && holder.firstChild) holder.removeChild(holder.firstChild);
      widgetIdRef.current = null;
    };
  }, [sitekey, theme, size, hl]);

  return <div ref={holderRef} {...rest} />;
});

export default SafeReCAPTCHA;
