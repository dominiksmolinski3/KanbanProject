import type { ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { useFloatingContainer } from '../ui/FloatingContainer';

export default function FloatingLayer({ children }: { children: ReactNode }) {
  const container = useFloatingContainer();
  return createPortal(children, container);
}
