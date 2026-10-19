import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { fetchSubTask } from '../../../services/api';
import { DESCRIPTION_MAX_LENGTH } from '../../../services/textLimits';
import type { SubTask } from '../../../types/api';
import LengthHint from '../../LengthHint';
import EditIcon from '../EditIcon';

interface SubtaskDescriptionProps {
  subtask: SubTask;
  readOnly: boolean;
  onSave: (description: string) => Promise<boolean>;
}

export default function SubtaskDescription({ subtask, readOnly, onSave }: SubtaskDescriptionProps) {
  const { t } = useTranslation();
  const [description, setDescription] = useState('');
  const [editing, setEditing] = useState(false);
  const input = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const data = await fetchSubTask(subtask.id);
        if (live) setDescription(data.description || '');
      } catch (error) {
        console.error('Error fetching subtask description:', error);
        if (live) setDescription('');
      }
    })();
    return () => {
      live = false;
    };
  }, [subtask.id]);

  useEffect(() => {
    if (editing) {
      input.current?.focus();
    }
  }, [editing]);

  const startEditing = () => {
    setDescription(subtask.description || '');
    setEditing(true);
  };

  const cancel = () => {
    setEditing(false);
    setDescription(subtask.description || '');
  };

  const save = async () => {
    if (await onSave(description)) {
      setEditing(false);
    }
  };

  return (
    <div className="subtask-description-container">
      {editing ? (
        <div className="description-edit-form">
          <textarea
            ref={input}
            value={description}
            maxLength={DESCRIPTION_MAX_LENGTH}
            onChange={(e) => setDescription(e.target.value)}
            placeholder={t('taskActions.description')}
            className="description-textarea"
            rows={4}
          ></textarea>
          <LengthHint value={description} max={DESCRIPTION_MAX_LENGTH} />
          <div className="description-edit-actions">
            <button onClick={save} className="save-description-btn">
              {t('taskActions.save')}
            </button>
            <button onClick={cancel} className="cancel-edit-btn">
              {t('taskActions.cancel')}
            </button>
          </div>
        </div>
      ) : (
        <div className="description-display">
          <div className="description-header">
            <h5>{t('taskActions.description')}:</h5>
            {!readOnly && (
              <button onClick={startEditing} className="edit-description-btn" title={t('taskActions.editSubTaskDescription')}>
                <EditIcon />
              </button>
            )}
          </div>
          {description ? (
            <p className="description-content">{description}</p>
          ) : (
            <p className="empty-description">{t('taskActions.noDescription')}</p>
          )}
        </div>
      )}
    </div>
  );
}
