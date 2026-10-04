import React, { useState, useEffect, useRef } from 'react';
import { useKanban } from '../context/KanbanContext';
import TaskDetails from './TaskDetails';
import EditableText from './EditableText';
import AvatarStack from './AvatarStack';
import TaskCardMeta from './TaskCardMeta';
import Icon from './Icon';
import Xarrow from "react-xarrows";
import { assignUserToTask, WipLimitExceededError } from '../services/api';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { deadlineState } from '../board/cardModel';
import '../styles/components/Task.css';

const TILT_GHOST_CLASS = 'task-drag-ghost';
const TITLE_OPEN_DELAY_MS = 250;

const linkColor = (token) => getComputedStyle(document.documentElement).getPropertyValue(token).trim() || 'currentColor';

function setTiltedDragImage(e, source) {
  if (!source || typeof e.dataTransfer.setDragImage !== 'function') return;
  const rect = source.getBoundingClientRect();
  const ghost = source.cloneNode(true);
  ghost.removeAttribute('id');
  ghost.classList.add(TILT_GHOST_CLASS);
  ghost.style.width = `${rect.width}px`;
  document.body.appendChild(ghost);
  e.dataTransfer.setDragImage(ghost, e.clientX - rect.left, e.clientY - rect.top);
  // The browser snapshots the ghost during dragstart, so it can leave the DOM on the next frame.
  requestAnimationFrame(() => ghost.remove());
}

function Task({ task, columnId, rowId }) {
  const {
    deleteTask,
    dragAndDrop,
    keyboardMove,
    refreshTasks,
    updateTaskName,
    updateTaskCompletion,
    setDailyFocus,
    readOnly,
    activeBoard,
    tasks = [],
    linkFocusId = null,
    setDraggedTaskId,
  } = useKanban();
  const [showDetails, setShowDetails] = useState(false);
  const [isConfirmingDelete, setIsConfirmingDelete] = useState(false);
  const [isDragOver, setIsDragOver] = useState(false);
  const [assignmentError, setAssignmentError] = useState(null);
  const [showWarning, setShowWarning] = useState(false);
  const [isParentTask] = useState(false); 
  const [childColumns] = useState(new Set());
  const [isDragging, setIsDragging] = useState(false);
 
  const taskRef = useRef(null);
  const warningTimeoutRef = useRef(null);
  const openTimerRef = useRef(null);
  const dueState = deadlineState(task.deadline);
  const isDeadlineExpired = dueState === 'overdue';
  const isDeadlineUpcoming = dueState === 'soon';
  const childTaskIds = task.childTaskIds || [];
  const isOnBoard = (id) => Boolean(document.getElementById(`task-${id}`));
  const parentTaskId = task.parentTaskId ?? null;

  const { t } = useTranslation();

  const hasUnfinishedSubtasks = (task.openSubtasks ?? 0) > 0;

  useEffect(() => () => {
    clearTimeout(openTimerRef.current);
    if (warningTimeoutRef.current) {
      clearTimeout(warningTimeoutRef.current);
    }
  }, []);

  useEffect(() => {
    if (assignmentError) {
      const timer = setTimeout(() => {
        setAssignmentError(null);
      }, 5000);
      
      return () => clearTimeout(timer);
    }
  }, [assignmentError]);

  const handleTaskClick = (e) => {
    if (e.target.classList.contains('editable-text')) {
      clearTimeout(openTimerRef.current);
      // A second click is the start of a double click, which edits the title in place.
      if (e.detail <= 1) {
        openTimerRef.current = setTimeout(() => setShowDetails(true), TITLE_OPEN_DELAY_MS);
      }
      return;
    }
    if (e.target.className === 'delete-btn' ||
        e.target.className === 'confirm-delete-btn' ||
        e.target.className === 'cancel-delete-btn' ||
        e.target.classList.contains('editable-text-input') ||
        e.target.classList.contains('warning-close-btn') ||
        e.target.classList.contains('task-complete-checkbox') ||
        e.target.classList.contains('daily-focus-btn') ||
        e.target.closest('.task-actions')) return;
    setShowDetails(!showDetails);
  };

  const handleDeleteClick = (e) => {
    e.stopPropagation();
    setIsConfirmingDelete(true);
  };

  const handleToggleCompletion = (e) => {
    e.stopPropagation();
    updateTaskCompletion(task.id, !task.completed);
  };

  const handleToggleDailyFocus = (e) => {
    e.stopPropagation();
    setDailyFocus(task.id, !task.dailyFocus);
  };

  const handleConfirmDelete = (e) => {
    e.stopPropagation();
    setIsConfirmingDelete(false);
    const taskElement = taskRef.current;
    taskElement.style.opacity = '0';
    taskElement.style.transform = 'translateX(10px)';
    setTimeout(() => {
      deleteTask(task.id);
    }, 200);
  };

  const handleCancelDelete = (e) => {
    e.stopPropagation();
    setIsConfirmingDelete(false);
  };

  const onDragStartHandler = (e) => {
    if (readOnly) {
      e.preventDefault();
      return;
    }
    const data = {
      id: task.id,
      type: 'task',
      sourceColumnId: columnId,
      sourceRowId: task.rowId,
      isParentTask: isParentTask,
      childColumns: Array.from(childColumns)
    };

    const dataString = JSON.stringify(data);
    e.dataTransfer.setData('application/task', dataString);
    e.dataTransfer.setData('taskId', task.id);
    e.dataTransfer.setData('columnId', columnId);
    e.dataTransfer.effectAllowed = 'move';
    setTiltedDragImage(e, taskRef.current);

    if (taskRef.current) {
      taskRef.current.classList.add('dragging');
    }

    setIsDragging(true);
    setDraggedTaskId?.(task.id);
    document.body.classList.add('showing-task-relationships');

    if (hasUnfinishedSubtasks) {
      setShowWarning(true);
    
      if (warningTimeoutRef.current) {
        clearTimeout(warningTimeoutRef.current);
      }
      
      warningTimeoutRef.current = setTimeout(() => {
        setShowWarning(false);
      }, 5000);
    }
  };

  const heldByKeyboard = keyboardMove.isHeld(task.id);
  const showsLinks = isDragging || heldByKeyboard;
  const focusTask = linkFocusId !== null && String(linkFocusId) !== String(task.id)
    ? tasks.find((candidate) => String(candidate.id) === String(linkFocusId))
    : null;
  let linkRole = null;
  if (focusTask && (focusTask.childTaskIds || []).some((id) => String(id) === String(task.id))) {
    linkRole = 'child';
  } else if (focusTask && String(focusTask.parentTaskId) === String(task.id)) {
    linkRole = 'parent';
  }

  const onGripKeyDown = (e) => {
    if (heldByKeyboard) {
      const direction = { ArrowLeft: 'left', ArrowRight: 'right', ArrowUp: 'up', ArrowDown: 'down' }[e.key];
      if (direction) {
        e.preventDefault();
        keyboardMove.step(direction);
        return;
      }
      if (e.key === ' ' || e.key === 'Enter') {
        e.preventDefault();
        keyboardMove.drop();
        return;
      }
      if (e.key === 'Escape') {
        e.preventDefault();
        keyboardMove.cancel();
      }
      return;
    }
    if (e.key === ' ') {
      if (readOnly) {
        return;
      }
      e.preventDefault();
      keyboardMove.grab(task, columnId, rowId ?? task.rowId);
      return;
    }
    if (e.key === 'Enter') {
      e.preventDefault();
      setShowDetails(true);
    }
  };

  const openDetails = (e) => {
    e.stopPropagation();
    setShowDetails(true);
  };

  const titleId = `task-title-${task.id}`;
  const title = task.title || t('board.keyboardMove.untitled');

  const onDragOver = (e) => {
    e.preventDefault();
    e.stopPropagation();
    
    if (e.dataTransfer.types.includes('application/task') || 
        e.dataTransfer.types.includes('application/user')) {
      setIsDragOver(true);
    }
  };
  
  const onDragLeave = (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);
  };
  
  const onDrop = async (e) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);
    if (readOnly) {
      return;
    }

    setShowWarning(false);
    if (warningTimeoutRef.current) {
      clearTimeout(warningTimeoutRef.current);
    }
    
    if (e.dataTransfer.types.includes('application/task')) {
      try {
        const dataString = e.dataTransfer.getData('application/task');
        const taskData = JSON.parse(dataString);
        const draggedTaskId = taskData.id;
        if (draggedTaskId === task.id) {
          return;
        }
        
        dragAndDrop.handleTaskReorder(draggedTaskId, task.id);
      } catch (err) {
        console.error('Error processing task drop for reordering:', err);
      }
    }
    
    if (e.dataTransfer.types.includes('application/user')) {
      try {
        const dataString = e.dataTransfer.getData('application/user');
        const userData = JSON.parse(dataString);
        
        if (userData.type === 'user') {
          const userId = userData.userId;
          const userName = userData.userName || String(userId);
          
          try {
            await assignUserToTask(task.id, parseInt(userId));
            refreshTasks();
            setAssignmentError(null);
            
            toast.success(t('notifications.userAssignedToTask', { user: userName, title: task.title }));
          } catch (error) {
            console.error('Error assigning user:', error);
            setAssignmentError(
              error instanceof WipLimitExceededError
                ? t('notifications.userWipLimitExceeded', {
                    name: userName,
                    limit: error.status?.wipLimit
                  })
                : t('notifications.userAssignError')
            );
          }
        }
      } catch (err) {
        console.error('Error processing user drop:', err);
        setAssignmentError(t('notifications.userAssignError'));
      }
    }
  };
  
  const onDragEndHandler = () => {
    setShowWarning(false);
    if (warningTimeoutRef.current) {
      clearTimeout(warningTimeoutRef.current);
    }
    if (taskRef.current) {
      taskRef.current.classList.remove('dragging');
    }
    setIsDragging(false);
    setDraggedTaskId?.(null);
    document.body.classList.remove('showing-task-relationships');

  };

  const handleCloseWarning = (e) => {
    e.stopPropagation();
    setShowWarning(false);
    if (warningTimeoutRef.current) {
      clearTimeout(warningTimeoutRef.current);
    }
  };

  return (
    <>
      <article
        ref={taskRef}
        id={`task-${task.id}`}
        className={[
          'task',
          isDragOver && 'user-drag-over',
          linkRole && `linked-${linkRole}`,
          heldByKeyboard && 'keyboard-held',
          isParentTask && 'parent-task',
          task.completed && 'task-completed',
          task.dailyFocus && 'daily-focus',
          isDeadlineExpired && 'deadline-expired',
          isDeadlineUpcoming && 'deadline-upcoming',
        ].filter(Boolean).join(' ')}
        draggable={!readOnly}
        aria-labelledby={titleId}
        onClick={handleTaskClick}
        onDragStart={onDragStartHandler}
        onDragEnd={onDragEndHandler}
        onDragOver={onDragOver}
        onDragLeave={onDragLeave}
        onDrop={onDrop}
        data-task-id={task.id}
        data-column-id={columnId}
        data-row-id={task.rowId || "null"}
        data-is-parent={isParentTask}
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
            onClick={openDetails}
          >
            <Icon name="open" />
          </button>
          <button
            type="button"
            className={`daily-focus-btn ${task.dailyFocus ? 'active' : ''}`}
            title={task.dailyFocus
              ? t('taskActions.removeFromDailyFocus')
              : t('taskActions.addToDailyFocus')}
            aria-label={task.dailyFocus
              ? t('taskActions.removeFromDailyFocus')
              : t('taskActions.addToDailyFocus')}
            aria-pressed={Boolean(task.dailyFocus)}
            onClick={handleToggleDailyFocus}
          >
            <Icon name="star" filled={Boolean(task.dailyFocus)} />
          </button>
          {!readOnly && (
            <button
              type="button"
              className="delete-btn"
              title={t('taskActions.delete')}
              aria-label={t('taskActions.delete')}
              onClick={handleDeleteClick}
            >
              <Icon name="trash" />
            </button>
          )}
        </div>

        {assignmentError && (
          <div className="assignment-error">
            {assignmentError}
          </div>
        )}

        {hasUnfinishedSubtasks && showWarning && (
          <div className="subtask-warning">
            <Icon name="warning" size="sm" className="warning-icon" />
            <div className="warning-message">
              {t('taskActions.incompleteSubtasks')}
            </div>
            <button
              type="button"
              className="warning-close-btn"
              aria-label={t('demo.dismiss')}
              onClick={handleCloseWarning}
            >
              <Icon name="close" size="sm" />
            </button>
          </div>
        )}
      </article>

      {showsLinks && childTaskIds.filter(isOnBoard).map(childId => (
        <Xarrow
          key={`arrow-${task.id}-${childId}`}
          start={`task-${task.id}`}
          end={`task-${childId}`}
          color={linkColor('--kb-accent')}
          strokeWidth={2}
          path="smooth"
          curveness={0.45}
          zIndex={20}
          showHead
          headSize={5}
          showTail
          tailShape="circle"
          tailSize={3}
        />
      ))}

      {showsLinks && parentTaskId !== null && isOnBoard(parentTaskId) && (
        <Xarrow
          key={`arrow-${task.id}-${parentTaskId}`}
          start={`task-${task.id}`}
          end={`task-${parentTaskId}`}
          color={linkColor('--kb-ink-muted')}
          strokeWidth={2}
          path="smooth"
          curveness={0.45}
          dashness={{ strokeLen: 6, nonStrokeLen: 5 }}
          zIndex={20}
          showHead
          headSize={5}
          showTail
          tailShape="circle"
          tailSize={3}
        />
      )}

      {isConfirmingDelete && (
        <div className="delete-modal-overlay" onClick={handleCancelDelete}>
          <div className="delete-modal" onClick={e => e.stopPropagation()}>
            <p>{t('taskActions.confirmDelete')}</p>
            <div className="confirmation-buttons">
              <button className="confirm-delete-btn" onClick={handleConfirmDelete}>
                {t('taskActions.yes')}
              </button>
              <button className="cancel-delete-btn" onClick={handleCancelDelete}>
                {t('taskActions.no')}
              </button>
            </div>
          </div>
        </div>
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