import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import { DESCRIPTION_MAX_LENGTH } from '../../services/textLimits';
import LengthHint from '../LengthHint';
import EditIcon from './EditIcon';
import type { TaskDetailsState } from './useTaskDetails';

export default function TaskDetailsDescription({ state }: { state: TaskDetailsState }) {
  const { t } = useTranslation();
  const { readOnly } = useKanban();
  const field = state.description;

  return (
    <section className="task-description-section">
      <div className="description-header">
        <h3>{t('taskActions.description')}</h3>
        {!field.editing && !readOnly && (
          <button
            onClick={field.start}
            className="edit-description-btn"
            aria-label={t('taskActions.editTaskDescription')}
            title={t('taskActions.editTaskDescription')}
          >
            <EditIcon />
          </button>
        )}
      </div>

      {field.editing ? (
        <div className="description-edit-form">
          <textarea
            ref={field.inputRef}
            value={field.value}
            maxLength={DESCRIPTION_MAX_LENGTH}
            onChange={(e) => field.setValue(e.target.value)}
            placeholder={t('taskActions.description')}
            className="description-textarea"
            rows={5}
          ></textarea>
          <LengthHint value={field.value} max={DESCRIPTION_MAX_LENGTH} />
          <div className="description-edit-actions">
            <button onClick={state.saveDescription} className="save-description-btn">
              {t('taskActions.save')}
            </button>
            <button onClick={field.cancel} className="cancel-edit-btn">
              {t('taskActions.cancel')}
            </button>
          </div>
        </div>
      ) : (
        <div className="description-display">
          {field.value ? (
            <p className="description-content">{field.value}</p>
          ) : (
            <p className="empty-description">{t('taskActions.noDescription')}</p>
          )}
        </div>
      )}
    </section>
  );
}
