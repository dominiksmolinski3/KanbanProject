import { act, renderHook } from '@testing-library/react';
import { useAsyncAction } from '../../hooks/useAsyncAction';

const deferred = () => {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => { resolve = res; reject = rej; });
  return { promise, resolve, reject };
};

describe('useAsyncAction', () => {
  test('is idle with no error to begin with', () => {
    const { result } = renderHook(() => useAsyncAction());

    expect(result.current.pending).toBe(false);
    expect(result.current.error).toBeNull();
  });

  test('is pending while the action runs and reports success when it settles', async () => {
    const { result } = renderHook(() => useAsyncAction());
    const gate = deferred();
    let outcome;

    act(() => { outcome = result.current.run(() => gate.promise); });
    expect(result.current.pending).toBe(true);

    await act(async () => { gate.resolve(); await outcome; });

    expect(await outcome).toBe(true);
    expect(result.current.pending).toBe(false);
    expect(result.current.error).toBeNull();
  });

  test('keeps the message of a failure and reports it', async () => {
    const { result } = renderHook(() => useAsyncAction());
    let outcome;

    await act(async () => { outcome = await result.current.run(() => Promise.reject(new Error('Name taken'))); });

    expect(outcome).toBe(false);
    expect(result.current.error).toBe('Name taken');
    expect(result.current.pending).toBe(false);
  });

  test('falls back to the given sentence when the failure has no message', async () => {
    const { result } = renderHook(() => useAsyncAction());

    await act(async () => { await result.current.run(() => Promise.reject(new Error('')), 'Could not add it'); });

    expect(result.current.error).toBe('Could not add it');
  });

  test('has no error to show when the failure has no message and no fallback was given', async () => {
    const { result } = renderHook(() => useAsyncAction());

    await act(async () => { await result.current.run(() => Promise.reject(new Error(''))); });

    expect(result.current.error).toBeNull();
  });

  test('clears the previous error when it starts again', async () => {
    const { result } = renderHook(() => useAsyncAction());
    await act(async () => { await result.current.run(() => Promise.reject(new Error('first'))); });
    expect(result.current.error).toBe('first');

    await act(async () => { await result.current.run(() => Promise.resolve()); });

    expect(result.current.error).toBeNull();
  });

  test('lets the caller set its own error, for validation', () => {
    const { result } = renderHook(() => useAsyncAction());

    act(() => result.current.setError('A name is required'));

    expect(result.current.error).toBe('A name is required');
  });

  test('hands a failure to the onError it was given, the latest one, as well as keeping the message', async () => {
    const first = jest.fn();
    const latest = jest.fn();
    const failure = new Error('Name taken');
    const { result, rerender } = renderHook(({ onError }) => useAsyncAction({ onError }), { initialProps: { onError: first } });
    rerender({ onError: latest });

    await act(async () => { await result.current.run(() => Promise.reject(failure)); });

    expect(first).not.toHaveBeenCalled();
    expect(latest).toHaveBeenCalledWith(failure);
    expect(result.current.error).toBe('Name taken');
  });

  test('does not call onError when the action succeeds', async () => {
    const onError = jest.fn();
    const { result } = renderHook(() => useAsyncAction({ onError }));

    await act(async () => { await result.current.run(() => Promise.resolve()); });

    expect(onError).not.toHaveBeenCalled();
  });

  test('run keeps its identity across renders', () => {
    const { result, rerender } = renderHook(() => useAsyncAction());
    const first = result.current.run;

    rerender();

    expect(result.current.run).toBe(first);
  });
});
