import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import EditIcon from './EditIcon';

const DAY_MS = 24 * 60 * 60 * 1000;

function TaskDeadline({ deadline, readOnly, onSave }) {
  const { t } = useTranslation();
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
      <div className="deadline-header">
        <span className="deadline-icon">
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor" width="18" height="18">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
        </span>
        <h4>{t('taskActions.deadline')}:</h4>
        {!editing && !readOnly && (
          <button
            onClick={startEditing}
            className="edit-description-btn"
            title={t('taskActions.editDeadline')}
          >
            <EditIcon />
          </button>
        )}
      </div>

      {editing ? (
        <div className="deadline-edit-form">
          <input
            type="datetime-local"
            value={value}
            onChange={(e) => setValue(e.target.value)}
            className="deadline-input"
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
        <div className={`deadline-content ${isExpired ? 'expired' : isUpcoming ? 'upcoming' : ''}`}>
          {deadline ? (
            <>
              {new Date(deadline).toLocaleString(undefined, {
                year: 'numeric',
                month: 'long',
                day: 'numeric',
                hour: '2-digit',
                minute: '2-digit'
              })}
              {isExpired && <span className="expired-tag">{t('taskActions.expired')}</span>}
              {isUpcoming && <span className="upcoming-tag">{t('taskActions.upcoming')}</span>}
            </>
          ) : (
            <p className="empty-deadline">{t('taskActions.noDeadline')}</p>
          )}
        </div>
      )}
    </div>
  );
}

export default TaskDeadline;
