import { useTranslation } from 'react-i18next';
import type { SubTask } from '../../../types/api';
import Icon from '../../Icon';
import SubtaskDescription from './SubtaskDescription';

interface SubtaskItemProps {
  subtask: SubTask;
  readOnly: boolean;
  expanded: boolean;
  onToggle: () => void;
  onExpand: () => void;
  onAskDelete: () => void;
  onSaveDescription: (description: string) => Promise<boolean>;
}

export default function SubtaskItem({
  subtask, readOnly, expanded, onToggle, onExpand, onAskDelete, onSaveDescription
}: SubtaskItemProps) {
  const { t } = useTranslation();
  const toggleLabel = expanded ? t('taskActions.hideDetails') : t('taskActions.showDetails');

  return (
    <div className={`subtask-item ${expanded ? 'expanded' : ''}`}>
      <div className="subtask-header">
        <input
          type="checkbox"
          checked={subtask.completed}
          onChange={onToggle}
          disabled={readOnly}
          id={`subtask-${subtask.id}`}
          className="subtask-checkbox"
        />
        <label htmlFor={`subtask-${subtask.id}`} className={subtask.completed ? 'completed' : ''}>
          {subtask.title}
        </label>

        <div className="subtask-actions">
          <button className="description-toggle-btn dark-bg-with-text" onClick={onExpand} title={toggleLabel}>
            <Icon name="chevron-down" size="sm" className={expanded ? 'arrow-icon rotated' : 'arrow-icon'} />
            <span className="visually-hidden">{toggleLabel}</span>
          </button>
          {!readOnly && (
            <button
              className="delete-subtask-btn"
              onClick={onAskDelete}
              title={t('taskActions.deleteSubTask')}
              aria-label={t('taskActions.deleteSubTask')}
            >
              <Icon name="trash" size="sm" />
            </button>
          )}
        </div>
      </div>

      {expanded && <SubtaskDescription subtask={subtask} readOnly={readOnly} onSave={onSaveDescription} />}
    </div>
  );
}
