import React, { useEffect, useImperativeHandle, useRef } from 'react';
import { loadRecaptcha } from '../services/recaptchaLoader';

const WIDGET_PAINT_TIMEOUT_MS = 5000;

const SafeReCAPTCHA = React.forwardRef(function SafeReCAPTCHA(
  { sitekey, theme, size, hl, onChange, onExpired, onErrored, onReady, onLoadError, ...rest },
  ref,
) {
  const holderRef = useRef(null);
  const widgetIdRef = useRef(null);

  const handlersRef = useRef(null);
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
    let stopWaitingForPaint = null;
    const holder = holderRef.current;

    const announceWhenPainted = (target) => {
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
          callback: (token) => handlersRef.current.onChange?.(token),
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
