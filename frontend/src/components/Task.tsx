import { memo, useContext, useState, useEffect, useRef, type MouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import { CardActionsContext, type CardActions } from '../context/kanban/cardActions';
import TaskDetails from './TaskDetails';
import EditableText from './EditableText';
import AvatarStack from './AvatarStack';
import TaskCardMeta from './TaskCardMeta';
import Icon from './Icon';
import TaskActions from './task/TaskActions';
import DeleteConfirmation from './task/DeleteConfirmation';
import TaskLinks from './task/TaskLinks';
import { handleGripKey } from './task/gripKeys';
import { linkRoleOf, type LinkRole } from './task/linkRole';
import { useTaskDragSource } from './task/useTaskDragSource';
import { useTaskDropTarget } from './task/useTaskDropTarget';
import { deadlineState } from '../board/cardModel';
import type { BoardMember, Id, Task as TaskModel } from '../types/api';
import '../styles/components/Task.css';

const TITLE_OPEN_DELAY_MS = 250;
const DELETE_FADE_MS = 200;
const NO_MEMBERS: BoardMember[] = [];

interface TaskProps {
  task: TaskModel;
  columnId: Id;
  rowId?: Id | null;
}

interface TaskCardProps extends TaskProps {
  actions: CardActions;
  readOnly: boolean;
  members: BoardMember[];
  heldByKeyboard: boolean;
  linkRole: LinkRole;
}

// The board's context changes on every drag and every edit; the card itself is drawn again only when these props do.
function Task({ task, columnId, rowId }: TaskProps) {
  const kanban = useKanban();
  const actions = useContext(CardActionsContext) ?? kanban;

  return (
    <TaskCard
      task={task}
      columnId={columnId}
      rowId={rowId}
      actions={actions}
      readOnly={kanban.readOnly}
      members={kanban.activeBoard?.members || NO_MEMBERS}
      heldByKeyboard={kanban.keyboardMove.isHeld(task.id)}
      linkRole={linkRoleOf(task, kanban.tasks ?? [], kanban.linkFocusId ?? null)}
    />
  );
}

const TaskCard = memo(function TaskCard({
  task, columnId, rowId, actions, readOnly, members, heldByKeyboard, linkRole,
}: TaskCardProps) {
  const { deleteTask, keyboardMove, refreshTasks, updateTaskName, updateTaskCompletion, setDailyFocus } = actions;
  const { t } = useTranslation();
  const [showDetails, setShowDetails] = useState(false);
  const [isConfirmingDelete, setIsConfirmingDelete] = useState(false);

  const taskRef = useRef<HTMLElement>(null);
  const openTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const drag = useTaskDragSource(task, columnId, taskRef, readOnly, actions);
  const drop = useTaskDropTarget(task, drag.dismissWarning, readOnly, actions);

  const dueState = deadlineState(task.deadline);
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
          <AvatarStack userIds={task.userIds || []} members={members} />
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
});

export default Task;
