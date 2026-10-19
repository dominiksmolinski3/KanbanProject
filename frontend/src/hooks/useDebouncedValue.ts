import { useEffect, useRef, useState } from 'react';

export function useDebouncedValue<T>(value: T, delayMs: number): T {
  const signature = JSON.stringify(value);
  const [settled, setSettled] = useState({ value, signature });
  const latest = useRef(value);
  latest.current = value;

  useEffect(() => {
    if (signature === settled.signature) {
      return undefined;
    }
    const timer = setTimeout(() => setSettled({ value: latest.current, signature }), delayMs);
    return () => clearTimeout(timer);
  }, [signature, settled.signature, delayMs]);

  return settled.value;
}
