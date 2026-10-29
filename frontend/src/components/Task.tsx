import { useState, useEffect, useRef, type MouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import TaskDetails from './TaskDetails';
import EditableText from './EditableText';
import AvatarStack from './AvatarStack';
import TaskCardMeta from './TaskCardMeta';
import Icon from './Icon';
import TaskActions from './task/TaskActions';
import DeleteConfirmation from './task/DeleteConfirmation';
import TaskLinks from './task/TaskLinks';
import { handleGripKey } from './task/gripKeys';
import { linkRoleOf } from './task/linkRole';
import { useTaskDragSource } from './task/useTaskDragSource';
import { useTaskDropTarget } from './task/useTaskDropTarget';
import { deadlineState } from '../board/cardModel';
import type { Id, Task as TaskModel } from '../types/api';
import '../styles/components/Task.css';

const TITLE_OPEN_DELAY_MS = 250;
const DELETE_FADE_MS = 200;

interface TaskProps {
  task: TaskModel;
  columnId: Id;
  rowId?: Id | null;
}

function Task({ task, columnId, rowId }: TaskProps) {
  const {
    deleteTask,
    keyboardMove,
    refreshTasks,
    updateTaskName,
    updateTaskCompletion,
    setDailyFocus,
    readOnly,
    activeBoard,
    tasks = [],
    linkFocusId = null,
  } = useKanban();
  const { t } = useTranslation();
  const [showDetails, setShowDetails] = useState(false);
  const [isConfirmingDelete, setIsConfirmingDelete] = useState(false);

  const taskRef = useRef<HTMLElement>(null);
  const openTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const drag = useTaskDragSource(task, columnId, taskRef);
  const drop = useTaskDropTarget(task, drag.dismissWarning);

  const dueState = deadlineState(task.deadline);
  const heldByKeyboard = keyboardMove.isHeld(task.id);
  const linkRole = linkRoleOf(task, tasks, linkFocusId);
  const titleId = `task-title-${task.id}`;
  const title = task.title || t('board.keyboardMove.untitled');

  useEffect(() => () => {
    if (openTimerRef.current) clearTimeout(openTimerRef.current);
  }, []);

  const handleTaskClick = (e: MouseEvent<HTMLElement>) => {
    const target = e.target as HTMLElement;
    if (target.classList.contains('editable-text')) {
      if (openTimerRef.current) clearTimeout(openTimerRef.current);
      // A second click is the start of a double click, which edits the title in place.
      if (e.detail <= 1) {
        openTimerRef.current = setTimeout(() => setShowDetails(true), TITLE_OPEN_DELAY_MS);
      }
      return;
    }
    if (target.className === 'delete-btn' ||
        target.className === 'confirm-delete-btn' ||
        target.className === 'cancel-delete-btn' ||
        target.classList.contains('editable-text-input') ||
        target.classList.contains('warning-close-btn') ||
        target.classList.contains('task-complete-checkbox') ||
        target.classList.contains('daily-focus-btn') ||
        target.closest('.task-actions')) return;
    setShowDetails(!showDetails);
  };

  const handleToggleCompletion = (e: MouseEvent | React.ChangeEvent) => {
    e.stopPropagation();
    updateTaskCompletion(task.id, !task.completed);
  };

  const handleToggleDailyFocus = (e: MouseEvent) => {
    e.stopPropagation();
    setDailyFocus(task.id, !task.dailyFocus);
  };

  const handleDeleteClick = (e: MouseEvent) => {
    e.stopPropagation();
    setIsConfirmingDelete(true);
  };

  const handleConfirmDelete = (e: MouseEvent) => {
    e.stopPropagation();
    setIsConfirmingDelete(false);
    const taskElement = taskRef.current;
    if (taskElement) {
      taskElement.style.opacity = '0';
      taskElement.style.transform = 'translateX(10px)';
    }
    setTimeout(async () => {
      try {
        await deleteTask(task.id);
      } catch {
        if (taskElement) {
          taskElement.style.opacity = '';
          taskElement.style.transform = '';
        }
      }
    }, DELETE_FADE_MS);
  };

  const handleCancelDelete = (e?: MouseEvent) => {
    e?.stopPropagation();
    setIsConfirmingDelete(false);
  };

  const openDetails = (e: MouseEvent) => {
    e.stopPropagation();
    setShowDetails(true);
  };

  return (
    <>
      <article
        ref={taskRef}
        id={`task-${task.id}`}
        className={[
          'task',
          drop.isDragOver && 'user-drag-over',
          linkRole && `linked-${linkRole}`,
          heldByKeyboard && 'keyboard-held',
          task.completed && 'task-completed',
          task.dailyFocus && 'daily-focus',
          dueState === 'overdue' && 'deadline-expired',
          dueState === 'soon' && 'deadline-upcoming',
        ].filter(Boolean).join(' ')}
        draggable={!readOnly}
        aria-labelledby={titleId}
        onClick={handleTaskClick}
        onDragStart={drag.onDragStart}
        onDragEnd={drag.onDragEnd}
        onDragOver={drop.onDragOver}
        onDragLeave={drop.onDragLeave}
        onDrop={drop.onDrop}
        data-task-id={task.id}
        data-column-id={columnId}
        data-row-id={task.rowId || "null"}
      >
        {heldByKeyboard && <span className="task-held-tag">{t('board.keyboardMove.tag')}</span>}
        {linkRole && (
          <span className={`task-held-tag link-tag ${linkRole}`}>
            {linkRole === 'child' ? t('taskDetails.childTask') : t('taskDetails.parentTask')}
          </span>
        )}
        <div className="task-header">
          <input
            type="checkbox"
            className="task-complete-checkbox"
            checked={Boolean(task.completed)}
            onChange={handleToggleCompletion}
            onClick={(e) => e.stopPropagation()}
            disabled={readOnly}
            title={task.completed ? t('taskActions.reopen') : t('taskActions.complete')}
            aria-label={task.completed ? t('taskActions.reopen') : t('taskActions.complete')}
          />
          <div className="task-content" id={titleId}>
            <EditableText
              id={task.id}
              text={title}
              onUpdate={updateTaskName}
              label={t('taskActions.editTitle')}
              className="task-title"
              inputClassName="task-title-input"
              type="task"
              disabled={readOnly}
            />
          </div>
          {task.dailyFocus && (
            <span className="task-focus-star" title={t('board.dailyFocus')}>
              <Icon name="star" filled />
            </span>
          )}
        </div>

        <TaskCardMeta task={task} dueState={dueState}>
          <AvatarStack userIds={task.userIds || []} members={activeBoard?.members || []} />
        </TaskCardMeta>

        <TaskActions
          task={task}
          title={title}
          readOnly={readOnly}
          heldByKeyboard={heldByKeyboard}
          onGripKeyDown={(e) => handleGripKey(e, {
            task, columnId, rowId, readOnly, keyboardMove,
            held: heldByKeyboard,
            openDetails: () => setShowDetails(true),
          })}
          onOpen={openDetails}
          onToggleFocus={handleToggleDailyFocus}
          onDelete={handleDeleteClick}
        />

        {drop.assignmentError && (
          <div className="assignment-error">
            {drop.assignmentError}
          </div>
        )}

        {drag.showWarning && (
          <div className="subtask-warning">
            <Icon name="warning" size="sm" className="warning-icon" />
            <div className="warning-message">
              {t('taskActions.incompleteSubtasks')}
            </div>
            <button
              type="button"
              className="warning-close-btn"
              aria-label={t('demo.dismiss')}
              onClick={drag.closeWarning}
            >
              <Icon name="close" size="sm" />
            </button>
          </div>
        )}
      </article>

      {(drag.isDragging || heldByKeyboard) && <TaskLinks task={task} />}

      {isConfirmingDelete && (
        <DeleteConfirmation onConfirm={handleConfirmDelete} onCancel={handleCancelDelete} />
      )}

      {showDetails && (
        <TaskDetails
          task={task}
          onClose={() => setShowDetails(false)}
          onSubtaskUpdate={refreshTasks}
        />
      )}
    </>
  );
}

export default Task;
