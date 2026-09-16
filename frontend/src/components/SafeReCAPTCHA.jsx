import React, { useEffect, useImperativeHandle, useRef } from 'react';
import { loadRecaptcha } from '../services/recaptchaLoader';

// How long to wait for the widget's iframe to report in before showing it anyway.
// Only a backstop: it must never be possible to strand a caller's placeholder.
const WIDGET_PAINT_TIMEOUT_MS = 5000;

/**
 * Renders a reCAPTCHA v2 checkbox by calling `grecaptcha.render` directly, replacing
 * `react-google-recaptcha`: that library left a stale `_widgetId` across a remount (StrictMode, or
 * returning to the login form) and rendered nothing on a cached reload where `?onload=` fires
 * before the script's `load` event. Here the widget lives in a node this component owns and
 * empties on unmount, and readiness is polled rather than pushed (see recaptchaLoader), so there
 * is no callback to miss. `onReady` fires once the widget is actually in the DOM, so a caller's
 * placeholder can be swapped for it exactly when there is something to show.
 */
const SafeReCAPTCHA = React.forwardRef(function SafeReCAPTCHA(
  { sitekey, theme, size, hl, onChange, onExpired, onErrored, onReady, onLoadError, ...rest },
  ref,
) {
  const holderRef = useRef(null);
  const widgetIdRef = useRef(null);

  // The widget captures its callbacks once, at render time. Holding them in a ref
  // keeps an inline arrow from a re-rendering parent out of the effect's
  // dependencies, which would otherwise tear the widget down on every keystroke.
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
    // The holder is this component's own div and lives as long as it does, so the
    // node captured here is the one the cleanup has to empty.
    const holder = holderRef.current;

    /**
     * `grecaptcha.render` returns as soon as the iframe exists, but the checkbox is drawn by that
     * iframe's own document a moment later - announcing readiness at render time would swap the
     * placeholder for a blank box, exactly what a remount looks like on a cached reload.
     */
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
        // `cancelled` covers the unmount; the empty check covers a widget that a
        // previous run of this effect already put there.
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
      // grecaptcha has no destroy(), so the widget is dropped by removing the node
      // it lives in. Leaving it would stack a second widget beside it on remount.
      while (holder && holder.firstChild) holder.removeChild(holder.firstChild);
      widgetIdRef.current = null;
    };
  }, [sitekey, theme, size, hl]);

  return <div ref={holderRef} {...rest} />;
});

export default SafeReCAPTCHA;
