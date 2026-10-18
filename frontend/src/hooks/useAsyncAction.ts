import { useCallback, useState } from 'react';

export function useAsyncAction() {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const run = useCallback(async (action: () => Promise<unknown>, fallback?: string): Promise<boolean> => {
    setPending(true);
    setError(null);
    try {
      await action();
      return true;
    } catch (err) {
      setError((err as Error).message || fallback || null);
      return false;
    } finally {
      setPending(false);
    }
  }, []);

  return { pending, error, setError, run };
}
