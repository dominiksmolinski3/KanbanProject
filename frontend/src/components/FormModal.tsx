import type { ReactNode } from 'react';
import Modal from './ui/Modal';
import '../styles/components/Forms.css';

interface FormModalProps {
  children: ReactNode;
  onClose: () => void;
  ariaLabel?: string;
}

function FormModal({ children, onClose, ariaLabel = 'Dialog' }: FormModalProps) {
  return (
    <Modal onClose={onClose} overlayClassName="modal-overlay" contentClassName="form-modal" label={ariaLabel}>
      {children}
    </Modal>
  );
}

export default FormModal;
