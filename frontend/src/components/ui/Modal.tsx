import { useCallback, useRef, useState, type ReactNode } from 'react';
import * as Dialog from '@radix-ui/react-dialog';
import { FloatingContainerProvider } from './FloatingContainer';

interface ModalProps {
  onClose: () => void;
  overlayClassName: string;
  contentClassName: string;
  label?: string;
  nested?: boolean;
  busy?: boolean;
  role?: 'dialog' | 'alertdialog';
  children: ReactNode;
}

export const ModalTitle = Dialog.Title;

export default function Modal({ onClose, overlayClassName, contentClassName, label, nested = false, busy, role, children }: ModalProps) {
  const contentRef = useRef<HTMLDivElement | null>(null);
  const [container, setContainer] = useState<HTMLElement | null>(null);

  const attach = useCallback((node: HTMLDivElement | null) => {
    contentRef.current = node;
    setContainer(node);
  }, []);

  const content = (
    <Dialog.Content
      ref={attach}
      className={contentClassName}
      {...(role ? { role } : {})}
      {...(label ? { 'aria-label': label, 'aria-labelledby': undefined } : {})}
      {...(busy ? { 'aria-busy': true } : {})}
      aria-describedby={undefined}
      onOpenAutoFocus={(event) => {
        event.preventDefault();
        contentRef.current?.focus();
      }}
      // Escape closes the topmost dialog only; the header menu also listens on document.
      onEscapeKeyDown={(event) => event.stopPropagation()}
    >
      {label ? <Dialog.Title hidden /> : null}
      <FloatingContainerProvider value={container}>{children}</FloatingContainerProvider>
    </Dialog.Content>
  );

  return (
    <Dialog.Root open onOpenChange={(open) => { if (!open) onClose?.(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className={overlayClassName}>{nested ? content : null}</Dialog.Overlay>
        {nested ? null : content}
      </Dialog.Portal>
    </Dialog.Root>
  );
}
