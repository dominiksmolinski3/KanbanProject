import React, { useEffect } from 'react';
import { useTranslation } from 'react-i18next';

function ConfirmDialog({ title, subject, onConfirm, onCancel }) {
  const { t } = useTranslation();

  useEffect(() => {
    const onKeyDown = (event) => {
      if (event.key === 'Escape') {
        // Capture on window runs before the panel's document listener, so Escape closes only the dialog.
        event.stopPropagation();
        onCancel();
      }
    };
    window.addEventListener('keydown', onKeyDown, true);
    return () => window.removeEventListener('keydown', onKeyDown, true);
  }, [onCancel]);

  return (
    <div className="delete-confirmation-overlay" onClick={onCancel}>
      <div className="delete-confirmation-dialog" onClick={(event) => event.stopPropagation()}>
        <h4>{title}</h4>
        <p>{title} <strong>{subject}</strong>?</p>
        <div className="confirmation-actions">
          <button onClick={onConfirm} className="confirm-btn">
            {t('taskActions.yes')}
          </button>
          <button onClick={onCancel} className="cancel-btn">
            {t('taskActions.no')}
          </button>
        </div>
      </div>
    </div>
  );
}

export default ConfirmDialog;
