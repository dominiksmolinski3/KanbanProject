import { useCallback, useRef, useState } from 'react';

interface AsyncActionOptions {
  onError?: (error: unknown) => void;
}

export function useAsyncAction({ onError }: AsyncActionOptions = {}) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const onErrorRef = useRef(onError);
  onErrorRef.current = onError;

  const run = useCallback(async (action: () => Promise<unknown>, fallback?: string): Promise<boolean> => {
    setPending(true);
    setError(null);
    try {
      await action();
      return true;
    } catch (err) {
      setError((err as Error).message || fallback || null);
      onErrorRef.current?.(err);
      return false;
    } finally {
      setPending(false);
    }
  }, []);

  return { pending, error, setError, run };
}
