import { useEffect } from 'react';

const DEFAULT_COLUMN_COUNT = 3;

export function useColumnCountVariable(count: number) {
  useEffect(() => {
    if (count > 0) {
      document.documentElement.style.setProperty('--column-count', String(count));
    }

    return () => {
      document.documentElement.style.setProperty('--column-count', String(DEFAULT_COLUMN_COUNT));
    };
  }, [count]);
}
