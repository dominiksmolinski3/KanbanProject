import { useRef, type ReactNode } from 'react';
import * as Dialog from '@radix-ui/react-dialog';

interface ModalProps {
  onClose: () => void;
  overlayClassName: string;
  contentClassName: string;
  label?: string;
  nested?: boolean;
  role?: 'dialog' | 'alertdialog';
  children: ReactNode;
}

export const ModalTitle = Dialog.Title;

export default function Modal({ onClose, overlayClassName, contentClassName, label, nested = false, role, children }: ModalProps) {
  const contentRef = useRef<HTMLDivElement>(null);

  const content = (
    <Dialog.Content
      ref={contentRef}
      className={contentClassName}
      {...(role ? { role } : {})}
      {...(label ? { 'aria-label': label, 'aria-labelledby': undefined } : {})}
      aria-describedby={undefined}
      onOpenAutoFocus={(event) => {
        event.preventDefault();
        contentRef.current?.focus();
      }}
      // Escape closes the topmost dialog only; a panel behind it listens on document.
      onEscapeKeyDown={(event) => event.stopPropagation()}
    >
      {label ? <Dialog.Title hidden /> : null}
      {children}
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
