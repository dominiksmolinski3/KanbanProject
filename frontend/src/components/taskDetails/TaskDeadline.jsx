import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import EditIcon from './EditIcon';

const DAY_MS = 24 * 60 * 60 * 1000;

function TaskDeadline({ deadline, readOnly, onSave }) {
  const { t, i18n } = useTranslation();
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState('');

  const isExpired = deadline && new Date(deadline) < new Date();
  const isUpcoming = deadline && !isExpired && (new Date(deadline) - new Date()) < DAY_MS;

  const startEditing = () => {
    setValue(deadline ? new Date(deadline).toISOString().slice(0, 16) : '');
    setEditing(true);
  };

  const save = async () => {
    if (await onSave({ deadline: value || null })) {
      setEditing(false);
    }
  };

  return (
    <div className="task-deadline-section">
      {editing ? (
        <div className="deadline-edit-form">
          <input
            type="datetime-local"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            className="deadline-input"
            aria-label={t('taskActions.deadline')}
          />
          <div className="description-edit-actions">
            <button onClick={save} className="save-description-btn">
              {t('taskActions.save')}
            </button>
            <button onClick={() => setEditing(false)} className="cancel-edit-btn">
              {t('taskActions.cancel')}
            </button>
          </div>
        </div>
      ) : (
        <div className="deadline-row">
          {deadline ? (
            <span className={`deadline-content ${isExpired ? 'expired' : isUpcoming ? 'upcoming' : ''}`}>
              {new Date(deadline).toLocaleString(i18n?.language || undefined, {
                year: 'numeric',
                month: 'long',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit'
              })}
              {isExpired && <span className="expired-tag">{t('taskActions.expired')}</span>}
              {isUpcoming && <span className="upcoming-tag">{t('taskActions.upcoming')}</span>}
            </span>
          ) : (
            <span className="empty-deadline">{t('taskActions.noDeadline')}</span>
          )}
          {!readOnly && (
            <button
              onClick={startEditing}
              className="edit-deadline-btn"
              aria-label={t('taskActions.editDeadline')}
              title={t('taskActions.editDeadline')}
            >
              <EditIcon />
            </button>
          )}
        </div>
      )}
    </div>
  );
}

export default TaskDeadline;
