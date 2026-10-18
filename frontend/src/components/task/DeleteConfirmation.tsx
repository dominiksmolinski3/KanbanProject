import type { MouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import Modal from '../ui/Modal';

interface DeleteConfirmationProps {
  onConfirm: (e: MouseEvent) => void;
  onCancel: (e?: MouseEvent) => void;
}

export default function DeleteConfirmation({ onConfirm, onCancel }: DeleteConfirmationProps) {
  const { t } = useTranslation();

  return (
    <Modal
      onClose={() => onCancel()}
      overlayClassName="delete-modal-overlay"
      contentClassName="delete-modal"
      label={t('taskActions.confirmDelete')}
      role="alertdialog"
      nested
    >
      <p>{t('taskActions.confirmDelete')}</p>
      <div className="confirmation-buttons">
        <button className="confirm-delete-btn" onClick={onConfirm}>
          {t('taskActions.yes')}
        </button>
        <button className="cancel-delete-btn" onClick={onCancel}>
          {t('taskActions.no')}
        </button>
      </div>
    </Modal>
  );
}
