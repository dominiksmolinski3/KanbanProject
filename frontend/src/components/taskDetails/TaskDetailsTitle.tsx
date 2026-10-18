import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import { NAME_MAX_LENGTH } from '../../services/textLimits';
import type { Task } from '../../types/api';
import LengthHint from '../LengthHint';
import Icon from '../Icon';
import EditIcon from './EditIcon';
import type { TaskDetailsState } from './useTaskDetails';

interface TaskDetailsTitleProps {
  task: Task;
  state: TaskDetailsState;
  titleId: string;
  title: string;
  onClose: () => void;
}

export default function TaskDetailsTitle({ task, state, titleId, title, onClose }: TaskDetailsTitleProps) {
  const { t } = useTranslation();
  const { readOnly, setDailyFocus, updateTaskCompletion } = useKanban();
  const field = state.title;
  const focusLabel = task.dailyFocus ? t('taskActions.removeFromDailyFocus') : t('taskActions.addToDailyFocus');
  const completeLabel = task.completed ? t('taskActions.reopen') : t('taskActions.complete');

  if (field.editing) {
    return (
      <div className="title-edit-form">
        <input
          ref={field.inputRef}
          type="text"
          value={field.value}
          maxLength={NAME_MAX_LENGTH}
          onChange={(e) => field.setValue(e.target.value)}
          className="title-input"
          placeholder={t('taskActions.editTitle')}
        />
        <LengthHint value={field.value} max={NAME_MAX_LENGTH} />
        <div className="title-edit-actions">
          <button onClick={state.saveTitle} className="save-title-btn" disabled={!field.value.trim()}>
            {t('taskActions.save')}
          </button>
          <button onClick={field.cancel} className="cancel-title-btn">
            {t('taskActions.cancel')}
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="sheet-titlerow">
      <input
        type="checkbox"
        className="sheet-check"
        checked={Boolean(task.completed)}
        disabled={readOnly || !updateTaskCompletion}
        onChange={() => updateTaskCompletion?.(task.id, !task.completed)}
        aria-label={completeLabel}
        title={completeLabel}
      />
      <h2 className="sheet-title" id={titleId}>{title}</h2>
      <div className="panel-actions">
        {setDailyFocus && (
          <button
            type="button"
            className={`daily-focus-toggle${task.dailyFocus ? ' active' : ''}`}
            aria-pressed={Boolean(task.dailyFocus)}
            onClick={() => setDailyFocus(task.id, !task.dailyFocus)}
            aria-label={focusLabel}
            title={focusLabel}
          >
            <Icon name="star" filled={Boolean(task.dailyFocus)} />
          </button>
        )}
        {!readOnly && (
          <button
            type="button"
            className="edit-title-btn"
            onClick={field.start}
            aria-label={t('taskActions.editTitle')}
            title={t('taskActions.editTitle')}
          >
            <EditIcon />
          </button>
        )}
        <button
          type="button"
          className="close-panel-btn"
          aria-label={t('taskDetails.close')}
          title={t('taskDetails.close')}
          onClick={(event) => {
            event.stopPropagation();
            onClose();
          }}
        >
          <Icon name="close" />
        </button>
      </div>
    </div>
  );
}
