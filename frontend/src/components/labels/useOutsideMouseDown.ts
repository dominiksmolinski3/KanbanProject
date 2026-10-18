import { useEffect } from 'react';

export function useOutsideMouseDown(
  active: boolean,
  insideSelector: string,
  onOutside: () => void,
  ignore?: () => Element | null
) {
  useEffect(() => {
    if (!active) return undefined;

    const handleMouseDown = (event: MouseEvent) => {
      const target = event.target as HTMLElement;
      if (!target.closest(insideSelector) && target !== ignore?.()) {
        onOutside();
      }
    };

    document.addEventListener('mousedown', handleMouseDown);
    return () => document.removeEventListener('mousedown', handleMouseDown);
  }, [active, insideSelector, onOutside, ignore]);
}
