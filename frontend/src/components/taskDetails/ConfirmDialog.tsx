import { useTranslation } from 'react-i18next';
import Modal, { ModalTitle } from '../ui/Modal';

interface ConfirmDialogProps {
  title: string;
  subject: string;
  onConfirm: () => void;
  onCancel: () => void;
}

function ConfirmDialog({ title, subject, onConfirm, onCancel }: ConfirmDialogProps) {
  const { t } = useTranslation();

  return (
    <Modal
      onClose={onCancel}
      overlayClassName="delete-confirmation-overlay"
      contentClassName="delete-confirmation-dialog"
      role="alertdialog"
      nested
    >
      <ModalTitle asChild><h4>{title}</h4></ModalTitle>
      <p>{title} <strong>{subject}</strong>?</p>
      <div className="confirmation-actions">
        <button onClick={onConfirm} className="confirm-btn">
          {t('taskActions.yes')}
        </button>
        <button onClick={onCancel} className="cancel-btn">
          {t('taskActions.no')}
        </button>
      </div>
    </Modal>
  );
}

export default ConfirmDialog;
