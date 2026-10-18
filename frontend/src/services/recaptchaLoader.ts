const API_SRC = 'https://www.google.com/recaptcha/api.js?render=explicit';
const API_SELECTOR = 'script[src*="recaptcha/api.js"]';
const READY_TIMEOUT_MS = 20000;
const POLL_INTERVAL_MS = 50;

export interface Grecaptcha {
  render: (...args: unknown[]) => unknown;
  reset?: (widgetId?: unknown) => void;
}

declare global {
  interface Window {
    grecaptcha?: Grecaptcha;
  }
}

let pending: Promise<Grecaptcha> | null = null;
let abandonPending: (() => void) | null = null;

function apiIfCallable(): Grecaptcha | null {
  const grecaptcha = typeof window !== 'undefined' ? window.grecaptcha : undefined;
  return grecaptcha && typeof grecaptcha.render === 'function' ? grecaptcha : null;
}

export function recaptchaLanguage(i18n?: { resolvedLanguage?: string; language?: string } | null): string {
  const tag = i18n?.resolvedLanguage || i18n?.language || 'en';
  return tag.split('-')[0];
}

export function resetRecaptchaLoader() {
  abandonPending?.();
  abandonPending = null;
  pending = null;
  if (!apiIfCallable()) {
    document.querySelectorAll(API_SELECTOR).forEach((script) => script.remove());
  }
}

export function loadRecaptcha(): Promise<Grecaptcha> {
  if (pending) return pending;

  pending = new Promise<Grecaptcha>((resolve, reject) => {
    let pollId: ReturnType<typeof setTimeout> | undefined;
    let settled = false;

    const stop = () => {
      settled = true;
      clearTimeout(pollId);
      clearTimeout(deadlineId);
    };

    const finish = <T>(settle: (value: T) => void, value: T) => {
      if (settled) return;
      stop();
      settle(value);
    };

    abandonPending = stop;

    const poll = () => {
      const grecaptcha = apiIfCallable();
      if (grecaptcha) finish(resolve, grecaptcha);
      else pollId = setTimeout(poll, POLL_INTERVAL_MS);
    };

    const deadlineId = setTimeout(
      () => finish(reject, new Error('reCAPTCHA did not finish loading in time')),
      READY_TIMEOUT_MS,
    );

    if (!document.querySelector(API_SELECTOR)) {
      const script = document.createElement('script');
      script.src = API_SRC;
      script.async = true;
      script.defer = true;
      script.onerror = () => {
        script.remove();
        finish(reject, new Error('reCAPTCHA script failed to load'));
      };
      document.head.appendChild(script);
    }

    poll();
  }).catch((error) => {
    pending = null;
    abandonPending = null;
    throw error;
  });

  return pending;
}
