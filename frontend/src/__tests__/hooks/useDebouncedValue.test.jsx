import { act, renderHook } from '@testing-library/react';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';

describe('useDebouncedValue', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('answers with the first value at once', () => {
    const { result } = renderHook(() => useDebouncedValue('a', 300));

    expect(result.current).toBe('a');
  });

  it('holds the old value until the new one has been still for the delay', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: 'a' },
    });

    rerender({ value: 'b' });
    act(() => jest.advanceTimersByTime(299));
    expect(result.current).toBe('a');

    act(() => jest.advanceTimersByTime(1));
    expect(result.current).toBe('b');
  });

  it('starts the wait again for every change, so a burst settles once on the last value', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: 'a' },
    });

    rerender({ value: 'b' });
    act(() => jest.advanceTimersByTime(200));
    rerender({ value: 'c' });
    act(() => jest.advanceTimersByTime(200));
    expect(result.current).toBe('a');

    act(() => jest.advanceTimersByTime(100));
    expect(result.current).toBe('c');
  });

  it('treats an object with the same contents as the same value', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: { term: 'x', labels: ['bug'] } },
    });
    const first = result.current;

    rerender({ value: { term: 'x', labels: ['bug'] } });
    act(() => jest.advanceTimersByTime(1000));

    expect(result.current).toBe(first);
  });

  it('settles on an object whose contents changed', () => {
    const { result, rerender } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: { term: 'x', labels: [] } },
    });

    rerender({ value: { term: 'x', labels: ['bug'] } });
    act(() => jest.advanceTimersByTime(300));

    expect(result.current).toEqual({ term: 'x', labels: ['bug'] });
  });

  it('leaves no timer behind when it goes away', () => {
    const { rerender, unmount } = renderHook(({ value }) => useDebouncedValue(value, 300), {
      initialProps: { value: 'a' },
    });
    rerender({ value: 'b' });

    unmount();

    expect(jest.getTimerCount()).toBe(0);
  });
});
