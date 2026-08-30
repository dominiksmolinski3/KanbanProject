import React from 'react';
import { render, act, waitFor } from '@testing-library/react';
import SafeReCAPTCHA from '../../components/SafeReCAPTCHA';
import { resetRecaptchaLoader } from '../../services/recaptchaLoader';

// Stands in for Google's global. render() injects a wrapper holding an iframe, as
// the real one does, and hands back a widget id. The iframe matters: the checkbox
// is painted by its document, after render() has already returned.
function installGrecaptcha() {
  let nextId = 0;
  window.grecaptcha = {
    render: jest.fn((container) => {
      const node = document.createElement('div');
      node.className = 'g-recaptcha-widget';
      node.appendChild(document.createElement('iframe'));
      container.appendChild(node);
      return nextId++;
    }),
    reset: jest.fn(),
    getResponse: jest.fn(() => 'a-token'),
    execute: jest.fn(),
  };
  return window.grecaptcha;
}

const widgets = (container) => container.querySelectorAll('.g-recaptcha-widget').length;

// The widget's iframe finishes loading and paints its checkbox.
function paintWidget(container) {
  container.querySelectorAll('.g-recaptcha-widget iframe').forEach((iframe) => {
    iframe.dispatchEvent(new Event('load'));
  });
}
const apiScripts = () =>
  [...document.querySelectorAll('script')].filter((s) => s.src.includes('recaptcha/api.js'));

function Host({ show, ...props }) {
  return <div>{show && <SafeReCAPTCHA sitekey="test-key" {...props} />}</div>;
}

beforeEach(() => {
  delete window.grecaptcha;
  resetRecaptchaLoader();
  apiScripts().forEach((script) => script.remove());
});

afterEach(() => {
  delete window.grecaptcha;
  resetRecaptchaLoader();
});

test('renders exactly one widget through the StrictMode double-mount', async () => {
  installGrecaptcha();

  const { container } = render(
    <React.StrictMode>
      <Host show />
    </React.StrictMode>,
  );

  await waitFor(() => expect(widgets(container)).toBe(1));
});

test('re-renders the widget after leaving and returning to the view', async () => {
  installGrecaptcha();

  const { container, rerender } = render(
    <React.StrictMode>
      <Host show />
    </React.StrictMode>,
  );
  await waitFor(() => expect(widgets(container)).toBe(1));

  // the password-reset view takes over, then the login form comes back
  rerender(
    <React.StrictMode>
      <Host show={false} />
    </React.StrictMode>,
  );
  rerender(
    <React.StrictMode>
      <Host show />
    </React.StrictMode>,
  );

  await waitFor(() => expect(widgets(container)).toBe(1));
});

/**
 * The reload bug. A cached api.js runs its `?onload=` callback during its own
 * execution, before the browser dispatches `load` on the script element - so a
 * loader that arms itself in the load handler never learns the API arrived. This
 * asserts the widget shows up whatever order those two events come in.
 */
test('renders when the API arrives before the script load event (cached api.js)', async () => {
  const { container } = render(
    <React.StrictMode>
      <Host show />
    </React.StrictMode>,
  );

  expect(apiScripts()).toHaveLength(1);
  expect(widgets(container)).toBe(0);

  const [script] = apiScripts();
  await act(async () => {
    installGrecaptcha();
    if (typeof window.onloadcallback === 'function') window.onloadcallback();
    if (script.onload) script.onload();
  });

  await waitFor(() => expect(widgets(container)).toBe(1));
});

test('renders when the API arrives well after mount', async () => {
  const { container } = render(
    <React.StrictMode>
      <Host show />
    </React.StrictMode>,
  );
  expect(widgets(container)).toBe(0);

  await act(async () => {
    installGrecaptcha();
  });

  await waitFor(() => expect(widgets(container)).toBe(1));
});

test('injects api.js only once across mounts', async () => {
  installGrecaptcha();

  const { rerender, container } = render(<Host show />);
  await waitFor(() => expect(widgets(container)).toBe(1));
  rerender(<Host show={false} />);
  rerender(<Host show />);
  await waitFor(() => expect(widgets(container)).toBe(1));

  expect(apiScripts()).toHaveLength(1);
});

/**
 * The placeholder the caller shows has to stay up until there is something to
 * look at. Readiness at render() time is too early - the box is still blank then,
 * which on a remount (api.js already cached) is the whole visible delay.
 */
test('does not signal ready until the widget iframe has painted', async () => {
  const onReady = jest.fn();
  installGrecaptcha();

  const { container } = render(<Host show onReady={onReady} />);

  await waitFor(() => expect(widgets(container)).toBe(1));
  expect(onReady).not.toHaveBeenCalled();

  act(() => paintWidget(container));
  expect(onReady).toHaveBeenCalledTimes(1);
});

test('signals ready only once the widget is actually in the DOM', async () => {
  const onReady = jest.fn();
  let widgetsWhenReadyFired = null;

  const { container } = render(
    <Host
      show
      onReady={() => {
        widgetsWhenReadyFired = widgets(container);
        onReady();
      }}
    />,
  );

  // nothing to show yet, so nothing has been announced
  expect(onReady).not.toHaveBeenCalled();

  await act(async () => {
    installGrecaptcha();
  });
  await waitFor(() => expect(widgets(container)).toBe(1));
  act(() => paintWidget(container));

  await waitFor(() => expect(onReady).toHaveBeenCalled());
  expect(widgetsWhenReadyFired).toBe(1);
});

test('shows the widget anyway if its iframe never reports in', async () => {
  jest.useFakeTimers();
  try {
    const onReady = jest.fn();
    installGrecaptcha();

    const { container } = render(<Host show onReady={onReady} />);
    await act(async () => {});

    expect(widgets(container)).toBe(1);
    expect(onReady).not.toHaveBeenCalled();

    act(() => jest.advanceTimersByTime(5000));
    expect(onReady).toHaveBeenCalledTimes(1);
  } finally {
    jest.useRealTimers();
  }
});

test('stops waiting for the paint when it unmounts mid-load', async () => {
  const onReady = jest.fn();
  installGrecaptcha();

  const { container, rerender } = render(<Host show onReady={onReady} />);
  await waitFor(() => expect(widgets(container)).toBe(1));

  const [iframe] = [...container.querySelectorAll('iframe')];
  rerender(<Host show={false} onReady={onReady} />);

  act(() => iframe.dispatchEvent(new Event('load')));
  expect(onReady).not.toHaveBeenCalled();
});

// What the "retry" link in the login form does: drop the failed attempt, then
// remount the widget. Without the drop the remount would just re-await the
// rejection that is already cached.
test('a reset lets the next mount start a fresh attempt', async () => {
  const onLoadError = jest.fn();
  const { container, rerender } = render(<Host show onLoadError={onLoadError} />);

  const [script] = apiScripts();
  await act(async () => script.onerror(new Event('error')));
  await waitFor(() => expect(onLoadError).toHaveBeenCalled());
  expect(widgets(container)).toBe(0);

  resetRecaptchaLoader();
  rerender(<Host show={false} onLoadError={onLoadError} />);
  installGrecaptcha();
  rerender(<Host show onLoadError={onLoadError} />);

  await waitFor(() => expect(widgets(container)).toBe(1));
});

test('reports a load failure through onLoadError', async () => {
  const onLoadError = jest.fn();

  render(<Host show onLoadError={onLoadError} />);

  const [script] = apiScripts();
  await act(async () => {
    script.onerror(new Event('error'));
  });

  await waitFor(() => expect(onLoadError).toHaveBeenCalled());
});

test('forwards onChange as the widget callback and resets through the ref', async () => {
  const grecaptcha = installGrecaptcha();
  const onChange = jest.fn();
  const ref = React.createRef();

  const { container } = render(<SafeReCAPTCHA ref={ref} sitekey="test-key" onChange={onChange} />);
  await waitFor(() => expect(widgets(container)).toBe(1));

  const options = grecaptcha.render.mock.calls[0][1];
  expect(options.sitekey).toBe('test-key');

  act(() => options.callback('the-token'));
  expect(onChange).toHaveBeenCalledWith('the-token');

  act(() => ref.current.reset());
  expect(grecaptcha.reset).toHaveBeenCalledWith(ref.current.getWidgetId());
});

test('an expiring token is reported as an empty value', async () => {
  const grecaptcha = installGrecaptcha();
  const onChange = jest.fn();

  const { container } = render(<SafeReCAPTCHA sitekey="test-key" onChange={onChange} />);
  await waitFor(() => expect(widgets(container)).toBe(1));

  act(() => grecaptcha.render.mock.calls[0][1]['expired-callback']());
  expect(onChange).toHaveBeenCalledWith(null);
});
