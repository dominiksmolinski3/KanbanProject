import { useEffect, type DragEvent } from 'react';

export const DROP_TARGET = 'drop-target';

export const markDropTarget = (e: DragEvent<Element>) => e.currentTarget.classList.add(DROP_TARGET);

export const unmarkDropTarget = (e: DragEvent<Element>) => {
  if (!e.currentTarget.contains(e.relatedTarget as Node | null)) {
    e.currentTarget.classList.remove(DROP_TARGET);
  }
};

export function useClearDropTargets() {
  useEffect(() => {
    const clearDropTargets = () => {
      document.querySelectorAll(`.${DROP_TARGET}`).forEach((node) => node.classList.remove(DROP_TARGET));
    };
    document.addEventListener('dragend', clearDropTargets);
    document.addEventListener('drop', clearDropTargets);
    return () => {
      document.removeEventListener('dragend', clearDropTargets);
      document.removeEventListener('drop', clearDropTargets);
    };
  }, []);
}
