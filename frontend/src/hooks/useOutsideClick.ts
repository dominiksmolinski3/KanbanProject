import { useEffect, useRef, type RefObject } from 'react';

export function useOutsideClick(ref: RefObject<HTMLElement | null>, active: boolean, onOutside: () => void) {
  const latest = useRef(onOutside);
  latest.current = onOutside;

  useEffect(() => {
    if (!active) {
      return undefined;
    }
    const listener = (event: MouseEvent) => {
      const node = ref.current;
      if (node && !node.contains(event.target as Node)) {
        latest.current();
      }
    };
    document.addEventListener('mousedown', listener);
    return () => document.removeEventListener('mousedown', listener);
  }, [ref, active]);
}
