import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { TaskPatch } from '../../types/api';
import { toInstant, toLocalInput } from '../../board/deadlines';
import EditIcon from './EditIcon';

const DAY_MS = 24 * 60 * 60 * 1000;

interface TaskDeadlineProps {
  deadline: string | null | undefined;
  readOnly: boolean;
  onSave: (fields: TaskPatch) => Promise<boolean | void>;
}

function TaskDeadline({ deadline, readOnly, onSave }: TaskDeadlineProps) {
  const { t, i18n } = useTranslation();
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState('');

  const expiry = deadline ? new Date(deadline).getTime() : null;
  const isExpired = expiry !== null && expiry < Date.now();
  const isUpcoming = expiry !== null && !isExpired && expiry - Date.now() < DAY_MS;

  const startEditing = () => {
    setValue(toLocalInput(deadline));
    setEditing(true);
  };

  const save = async () => {
    if (await onSave({ deadline: toInstant(value) })) {
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
