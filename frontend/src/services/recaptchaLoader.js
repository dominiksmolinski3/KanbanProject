/**
 * Loads Google's reCAPTCHA api.js once per page and resolves when the widget API is actually
 * callable, decided by polling for `grecaptcha.render` rather than trusting the `?onload=`
 * callback: a cached api.js invokes that callback during its own execution, before the browser
 * dispatches `load`, so anything arming itself in the load handler misses it.
 */
const API_SRC = 'https://www.google.com/recaptcha/api.js?render=explicit';
const API_SELECTOR = 'script[src*="recaptcha/api.js"]';
const READY_TIMEOUT_MS = 20000;
const POLL_INTERVAL_MS = 50;

let pending = null;
let abandonPending = null;

function apiIfCallable() {
  const grecaptcha = typeof window !== 'undefined' ? window.grecaptcha : undefined;
  return grecaptcha && typeof grecaptcha.render === 'function' ? grecaptcha : null;
}

/**
 * Drops the cached attempt so the next `loadRecaptcha()` starts over, removing an errored script
 * tag with it so the retry doesn't poll a corpse. An attempt still in flight is abandoned rather
 * than settled, so nothing keeps polling on a timer behind the caller's back.
 */
export function resetRecaptchaLoader() {
  abandonPending?.();
  abandonPending = null;
  pending = null;
  if (!apiIfCallable()) {
    document.querySelectorAll(API_SELECTOR).forEach((script) => script.remove());
  }
}

export function loadRecaptcha() {
  if (pending) return pending;

  pending = new Promise((resolve, reject) => {
    let pollId = null;
    let deadlineId = null;
    let settled = false;

    const stop = () => {
      settled = true;
      clearTimeout(pollId);
      clearTimeout(deadlineId);
    };

    const finish = (settle, value) => {
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

    deadlineId = setTimeout(
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
    // A failed attempt must not be remembered, or the retry has nothing to do.
    pending = null;
    abandonPending = null;
    throw error;
  });

  return pending;
}
