import type { KeyboardEvent, MouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import Icon from '../Icon';
import type { Task } from '../../types/api';

interface TaskActionsProps {
  task: Task;
  title: string;
  readOnly: boolean;
  heldByKeyboard: boolean;
  onGripKeyDown: (e: KeyboardEvent) => void;
  onOpen: (e: MouseEvent) => void;
  onToggleFocus: (e: MouseEvent) => void;
  onDelete: (e: MouseEvent) => void;
}

export default function TaskActions({ task, title, readOnly, heldByKeyboard, onGripKeyDown, onOpen, onToggleFocus, onDelete }: TaskActionsProps) {
  const { t } = useTranslation();
  const focusLabel = task.dailyFocus
    ? t('taskActions.removeFromDailyFocus')
    : t('taskActions.addToDailyFocus');

  return (
    <div className="task-actions">
      {!readOnly && (
        <button
          type="button"
          className="task-grip"
          aria-roledescription={t('board.keyboardMove.roleDescription')}
          aria-label={t('board.keyboardMove.grip', { title })}
          title={t('board.keyboardMove.grip', { title })}
          aria-describedby="board-keyboard-move-help"
          aria-pressed={heldByKeyboard}
          onKeyDown={onGripKeyDown}
          onClick={(e) => e.stopPropagation()}
        >
          <Icon name="grip" />
        </button>
      )}
      <button
        type="button"
        className="task-open-btn"
        title={t('taskActions.open', { title })}
        aria-label={t('taskActions.open', { title })}
        onClick={onOpen}
      >
        <Icon name="open" />
      </button>
      <button
        type="button"
        className={`daily-focus-btn ${task.dailyFocus ? 'active' : ''}`}
        title={focusLabel}
        aria-label={focusLabel}
        aria-pressed={Boolean(task.dailyFocus)}
        onClick={onToggleFocus}
      >
        <Icon name="star" filled={Boolean(task.dailyFocus)} />
      </button>
      {!readOnly && (
        <button
          type="button"
          className="delete-btn"
          title={t('taskActions.delete')}
          aria-label={t('taskActions.delete')}
          onClick={onDelete}
        >
          <Icon name="trash" />
        </button>
      )}
    </div>
  );
}
