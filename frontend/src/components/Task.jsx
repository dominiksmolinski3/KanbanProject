import React, { useState, useEffect, useRef } from 'react';
import { useKanban } from '../context/KanbanContext';
import TaskDetails from './TaskDetails';
import EditableText from './EditableText';
import AvatarStack from './AvatarStack';
import TaskCardMeta from './TaskCardMeta';
import Xarrow from "react-xarrows";
import { assignUserToTask, fetchSubTasksByTaskId, fetchTask, WipLimitExceededError } from '../services/api';
import { createPortal } from 'react-dom';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { deadlineState } from '../board/cardModel';
import '../styles/components/BoardTokens.css';
import '../styles/components/Task.css';

const TILT_GHOST_CLASS = 'task-drag-ghost';

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
  } = useKanban();
  const [showDetails, setShowDetails] = useState(false);
  const [isConfirmingDelete, setIsConfirmingDelete] = useState(false);
  const [isDragOver, setIsDragOver] = useState(false);
  const [assignmentError, setAssignmentError] = useState(null);
  const [showWarning, setShowWarning] = useState(false);
  const [showDescription, setShowDescription] = useState(false);
  const [taskDescription, setTaskDescription] = useState('');
  const [loadingDescription, setLoadingDescription] = useState(false);
  const [isParentTask] = useState(false); 
  const [childColumns] = useState(new Set());
  const [isDragging, setIsDragging] = useState(false);
  const [taskSubtasks, setTaskSubtasks] = useState([]);
 
  const descriptionBtnRef = useRef(null);
  const taskRef = useRef(null);
  const warningTimeoutRef = useRef(null);
  const dueState = deadlineState(task.deadline);
  const isDeadlineExpired = dueState === 'overdue';
  const isDeadlineUpcoming = dueState === 'soon';
  const childTaskIds = task.childTaskIds || [];
  const isOnBoard = (id) => Boolean(document.getElementById(`task-${id}`));
  const parentTaskId = task.parentTaskId ?? null;

  const { t } = useTranslation();

  const hasUnfinishedSubtasks = (task.openSubtasks ?? 0) > 0;

  useEffect(() => () => {
    if (warningTimeoutRef.current) {
      clearTimeout(warningTimeoutRef.current);
    }
  }, []);

  useEffect(() => {
    setTaskSubtasks([]);
  }, [task.openSubtasks]);

  useEffect(() => {
    if (assignmentError) {
      const timer = setTimeout(() => {
        setAssignmentError(null);
      }, 5000);
      
      return () => clearTimeout(timer);
    }
  }, [assignmentError]);

  useEffect(() => {
    const handleClickOutside = (event) => {
      if (showDescription && 
          !event.target.classList.contains('description-dropdown-btn') && 
          !event.target.closest('.description-popover')) {
        setShowDescription(false);
      }
    };
    
    document.addEventListener('mousedown', handleClickOutside);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
    };
  }, [showDescription]);

  useEffect(() => {
    if (showDescription) {
      if (!taskSubtasks.length) {
        setLoadingDescription(true);
        
        Promise.all([
          fetchTask(task.id).then(taskData => setTaskDescription(taskData.description || '')),
          fetchSubTasksByTaskId(task.id).then(subtasks => setTaskSubtasks(subtasks || []))
        ])
        .catch(error => console.error('Error fetching task details:', error))
        .finally(() => setLoadingDescription(false));
      }

      window.dispatchEvent(new CustomEvent('close-all-popovers', {
        detail: { exceptTaskId: task.id }
      }));
    }
  }, [showDescription, task.id, taskSubtasks.length]);

  useEffect(() => {
    return () => {
      const popover = document.querySelector(`.description-popover[data-task-id="${task.id}"]`);
      if (popover && popover.parentNode) {
        popover.parentNode.removeChild(popover);
      }
    };
  }, [task.id]);

  useEffect(() => {
    const handleClosePopovers = (e) => {
      if (!e.detail || e.detail.exceptTaskId !== task.id) {
        setShowDescription(false);
      }
    };
    
    window.addEventListener('close-all-popovers', handleClosePopovers);
    
    return () => {
      window.removeEventListener('close-all-popovers', handleClosePopovers);
    };
  }, [task.id]);

  const handleTaskClick = (e) => {
    if (e.target.className === 'delete-btn' || 
        e.target.className === 'confirm-delete-btn' || 
        e.target.className === 'cancel-delete-btn' ||
        e.target.classList.contains('editable-text') ||
        e.target.classList.contains('editable-text-input') ||
        e.target.classList.contains('warning-close-btn') ||
        e.target.classList.contains('task-complete-checkbox') ||
        e.target.classList.contains('daily-focus-btn') ||
        e.target.classList.contains('description-dropdown-btn') ||
        e.target.closest('.description-dropdown-btn') ||
        e.target.closest('.task-description-dropdown')) return;
    setShowDetails(!showDetails);
  };

  const handleDescriptionToggle = (e) => {
    e.stopPropagation();
    
    if (showDescription) {
      setShowDescription(false);
      return;
    }
    
    setShowDescription(true);
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

  const onKeyDown = (e) => {
    if (e.target !== e.currentTarget) {
      return;
    }
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
      setShowDetails(shown => !shown);
    }
  };

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
      <div
        ref={taskRef}
        id={`task-${task.id}`}
        className={[
          'task',
          isDragOver && 'user-drag-over',
          heldByKeyboard && 'keyboard-held',
          isParentTask && 'parent-task',
          task.completed && 'task-completed',
          task.dailyFocus && 'daily-focus',
          isDeadlineExpired && 'deadline-expired',
          isDeadlineUpcoming && 'deadline-upcoming',
        ].filter(Boolean).join(' ')}
        draggable={!readOnly}
        tabIndex={0}
        role="button"
        aria-roledescription={t('board.keyboardMove.roleDescription')}
        aria-label={task.title || t('board.keyboardMove.untitled')}
        aria-describedby="board-keyboard-move-help"
        onClick={handleTaskClick}
        onKeyDown={onKeyDown}
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
        <div className="task-header">
          <input
            type="checkbox"
            className="task-complete-checkbox"
            checked={Boolean(task.completed)}
            onChange={handleToggleCompletion}
            onClick={(e) => e.stopPropagation()}
            title={task.completed ? t('taskActions.reopen') : t('taskActions.complete')}
            aria-label={task.completed ? t('taskActions.reopen') : t('taskActions.complete')}
          />
          <div className="task-content">
            <EditableText
              id={task.id}
              text={task.title || t('board.keyboardMove.untitled')}
              onUpdate={updateTaskName}
              className="task-title"
              inputClassName="task-title-input"
              type="task"
              disabled={readOnly}
            />
          </div>

          <div className="task-header-actions">
            <button
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
              ★
            </button>
            {!readOnly && (
              <button
                className="delete-btn"
                title={t('taskActions.delete')}
                aria-label={t('taskActions.delete')}
                onClick={handleDeleteClick}
              >
                ×
              </button>
            )}
          </div>
        </div>

        <TaskCardMeta task={task} dueState={dueState} />

        <div className="task-footer">
          <button
            ref={descriptionBtnRef}
            type="button"
            className="description-dropdown-btn"
            title={showDescription ? t('taskActions.hideDetails') : t('taskActions.showDetails')}
            aria-expanded={showDescription}
            onClick={handleDescriptionToggle}
          >
            <span className="description-dropdown-caret" aria-hidden="true">{showDescription ? '▴' : '▾'}</span>
            {showDescription ? t('taskActions.hideDetails') : t('taskActions.showDetails')}
          </button>
          <AvatarStack userIds={task.userIds || []} members={activeBoard?.members || []} />
        </div>

        {assignmentError && (
          <div className="assignment-error">
            {assignmentError}
          </div>
        )}

        {hasUnfinishedSubtasks && showWarning && (
          <div className="subtask-warning">
            <div className="warning-icon">⚠️</div>
            <div className="warning-message">
              {t('taskActions.incompleteSubtasks')}
            </div>
          <button 
            className="warning-close-btn" 
            onClick={handleCloseWarning}
          >
            ×
          </button>
          </div>
        )}
      </div>

      {isDragging && childTaskIds.filter(isOnBoard).map(childId => (
        <Xarrow
          key={`arrow-${task.id}-${childId}`}
          start={`task-${task.id}`}
          end={`task-${childId}`}
          color="#86d6ff"
          strokeWidth={3}
          path="smooth"
          startAnchor="auto"
          endAnchor="auto"
          curveness={0.3}
          zIndex={9999}
          animateDrawing={0.5}
          showHead={true}
          headSize={6}
          labels={{ middle: 
            <div style={{ 
              width: '12px', 
              height: '12px', 
              borderRadius: '50%', 
              backgroundColor: '#86d6ff',
              boxShadow: '0 0 5px rgba(134, 214, 255, 0.8)'
            }}/>
          }}
        />
      ))}

      {isDragging && parentTaskId !== null && isOnBoard(parentTaskId) && (
        <Xarrow
          key={`arrow-${parentTaskId}-${task.id}`}
          start={`task-${parentTaskId}`}
          end={`task-${task.id}`}
          color="#0e1b36"
          strokeWidth={3}
          path="straight"
          startAnchor="auto"
          endAnchor="auto"
          dashness={{ strokeLen: 5, nonStrokeLen: 5, animation: 1 }}
          zIndex={9999}
          showHead={true}
        />
      )}

      {showDescription && createPortal(
      <div 
        className="description-popover" 
        data-task-id={task.id} 
        style={{
          position: 'fixed', 
          opacity: 1,
          zIndex: 1000,
          left: descriptionBtnRef.current ? 
            descriptionBtnRef.current.getBoundingClientRect().left : window.innerWidth / 2 - 150,
          top: descriptionBtnRef.current ? 
            descriptionBtnRef.current.getBoundingClientRect().bottom + 5 : 100,
          width: '300px',
          visibility: 'visible' 
        }}
      >
        <div className="description-popover-arrow" style={{left: '50%'}}></div>
        <div className="description-popover-content">
          {loadingDescription ? (
            <p className="loading-description">{t('taskActions.loading')}</p>
          ) : (
            <>
              <div className="popover-section">
                <h4 className="popover-section-title">{t('taskActions.description')}</h4>
                {taskDescription ? (
                  <p className="description-content">{taskDescription}</p>
                ) : (
                  <p className="empty-description">{t('taskActions.noDescription')}</p>
                )}
              </div>
          
              <div className="popover-section subtasks-preview">
                <h4 className="popover-section-title">{t('taskActions.subtasks')}</h4>
                {taskSubtasks && taskSubtasks.length > 0 ? (
                  <ul className="subtasks-preview-list">
                    {taskSubtasks.map(subtask => (
                      <li 
                        key={subtask.id} 
                        className={`subtask-preview-item ${subtask.completed ? 'completed' : ''}`}
                      >
                        <span className={`subtask-checkbox ${subtask.completed ? 'checked' : ''}`}>
                          {subtask.completed ? '✓' : ''}
                        </span>
                        <span className="subtask-title">{subtask.title}</span>
                      </li>
                    ))}
                  </ul>
                ) : (
                  <p className="empty-subtasks">{t('taskActions.noSubtasks')}</p>
                )}
              </div>
            </>
          )}
        </div>
      </div>,
      document.body
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