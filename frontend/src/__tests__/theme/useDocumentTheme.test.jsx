import { act, renderHook, waitFor } from '@testing-library/react';
import { useDocumentTheme } from '../../theme/useDocumentTheme';

describe('useDocumentTheme', () => {
  afterEach(() => {
    document.documentElement.removeAttribute('data-theme');
  });

  test('reads the theme the pre-paint script set', () => {
    document.documentElement.dataset.theme = 'dark';
    const { result } = renderHook(() => useDocumentTheme());
    expect(result.current).toBe('dark');
  });

  test('treats a page with no theme set as light', () => {
    const { result } = renderHook(() => useDocumentTheme());
    expect(result.current).toBe('light');
  });

  test('follows a later change of the attribute', async () => {
    document.documentElement.dataset.theme = 'light';
    const { result } = renderHook(() => useDocumentTheme());

    act(() => {
      document.documentElement.dataset.theme = 'dark';
    });
    await waitFor(() => expect(result.current).toBe('dark'));

    act(() => {
      document.documentElement.dataset.theme = 'light';
    });
    await waitFor(() => expect(result.current).toBe('light'));
  });
});
