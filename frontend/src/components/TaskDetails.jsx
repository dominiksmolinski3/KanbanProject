import React, { useCallback, useEffect, useRef, useState } from 'react';
import LengthHint from './LengthHint';
import { DESCRIPTION_MAX_LENGTH, NAME_MAX_LENGTH } from '../services/textLimits';
import { useKanban } from '../context/KanbanContext';
import { createPortal } from 'react-dom';
import { fetchUsers, fetchTask, removeUserFromTask, updateTask, ConcurrentModificationError } from '../services/api';
import '../styles/components/TaskDetails.css';
import TaskLabels from './TaskLabels';
import TaskComments from './TaskComments';
import TaskSubtasks from './taskDetails/TaskSubtasks';
import TaskAttachments from './taskDetails/TaskAttachments';
import TaskAssignees, { AssignedUsersBar } from './taskDetails/TaskAssignees';
import TaskRelations from './taskDetails/TaskRelations';
import TaskDeadline from './taskDetails/TaskDeadline';
import TaskColumnHistory from './taskDetails/TaskColumnHistory';
import ConfirmDialog from './taskDetails/ConfirmDialog';
import EditIcon from './taskDetails/EditIcon';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';

const SAVED_NOTICE_MS = 3000;

function TaskDetails({ task, onClose, onSubtaskUpdate }) {
  const { refreshTasks, readOnly } = useKanban();
  const { t } = useTranslation();
  const [loading, setLoading] = useState(true);
  const [success, setSuccess] = useState(false);
  const [users, setUsers] = useState([]);
  const [assignedUsers, setAssignedUsers] = useState([]);
  const [userToRemove, setUserToRemove] = useState(null);
  const [parentTaskId, setParentTaskId] = useState(null);
  const [taskLabels, setTaskLabels] = useState([]);
  const [taskVersion, setTaskVersion] = useState(null);
  const [taskTitle, setTaskTitle] = useState('');
  const [originalTaskTitle, setOriginalTaskTitle] = useState('');
  const [editingTaskTitle, setEditingTaskTitle] = useState(false);
  const [taskDescription, setTaskDescription] = useState('');
  const [originalTaskDescription, setOriginalTaskDescription] = useState('');
  const [editingTaskDescription, setEditingTaskDescription] = useState(false);
  const [currentView, setCurrentView] = useState('main');

  const panelRef = useRef(null);
  const taskDescriptionInputRef = useRef(null);
  const taskTitleInputRef = useRef(null);
  const loadedTaskId = useRef(null);
  const savedTimer = useRef(null);

  useEffect(() => {
    const handleEscape = (event) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', handleEscape);
    return () => document.removeEventListener('keydown', handleEscape);
  }, [onClose]);

  useEffect(() => () => clearTimeout(savedTimer.current), []);

  const flashSaved = useCallback(() => {
    setSuccess(true);
    clearTimeout(savedTimer.current);
    savedTimer.current = setTimeout(() => setSuccess(false), SAVED_NOTICE_MS);
  }, []);

  const loadTaskData = async () => {
    try {
      if (loadedTaskId.current !== task.id) {
        setLoading(true);
      }
      const taskData = await fetchTask(task.id);
      setTaskVersion(typeof taskData.version === 'number' ? taskData.version : null);
      setParentTaskId(taskData.parentTaskId || null);

      const usersData = (await fetchUsers()) || [];
      const assignedIds = taskData.userIds || [];
      setUsers(usersData);
      setAssignedUsers(usersData.filter(user => assignedIds.includes(user.id)));

      const description = taskData.description || '';
      setTaskDescription(description);
      setOriginalTaskDescription(description);
      setTaskTitle(taskData.title);
      setOriginalTaskTitle(taskData.title);
      setTaskLabels(taskData.labels || []);

      loadedTaskId.current = task.id;
    } catch (error) {
      console.error('Error loading task data:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadTaskData();
  }, [task.id]);

  useEffect(() => {
    const panel = panelRef.current;
    if (loading || !panel) return;

    const rect = panel.getBoundingClientRect();
    if (rect.right > window.innerWidth) {
      panel.style.left = `${window.innerWidth - rect.width - 20}px`;
    }
    if (rect.bottom > window.innerHeight) {
      panel.style.top = `${window.innerHeight - rect.height - 20}px`;
    }
  }, [loading]);

  const reloadAfterChange = async () => {
    await loadTaskData();
    refreshTasks();
    flashSaved();
  };

  const persistTaskFields = async (fields) => {
    const payload = typeof taskVersion === 'number' ? { ...fields, version: taskVersion } : fields;
    const updated = await updateTask(task.id, payload);
    if (updated && typeof updated.version === 'number') {
      setTaskVersion(updated.version);
    }
    return updated;
  };

  const reportSaveError = (error, logLabel) => {
    if (error instanceof ConcurrentModificationError) {
      toast.info(t('notifications.taskChangedElsewhere'));
      loadTaskData();
      return;
    }
    console.error(logLabel, error);
    toast.error(t('notifications.errorOccurred', { message: error.message }));
  };

  const saveFields = async (fields, logLabel) => {
    try {
      await persistTaskFields(fields);
      flashSaved();
      return true;
    } catch (error) {
      reportSaveError(error, logLabel);
      return false;
    }
  };

  const startEditingTaskTitle = () => {
    setOriginalTaskTitle(taskTitle);
    setEditingTaskTitle(true);
    setTimeout(() => taskTitleInputRef.current?.focus(), 0);
  };

  const saveTaskTitle = async () => {
    if (await saveFields({ title: taskTitle }, 'Error saving task title:')) {
      setOriginalTaskTitle(taskTitle);
      setEditingTaskTitle(false);
      refreshTasks();
    }
  };

  const cancelEditingTaskTitle = () => {
    setTaskTitle(originalTaskTitle);
    setEditingTaskTitle(false);
  };

  const startEditingTaskDescription = () => {
    setOriginalTaskDescription(taskDescription);
    setEditingTaskDescription(true);
    setTimeout(() => taskDescriptionInputRef.current?.focus(), 0);
  };

  const saveTaskDescription = async () => {
    if (await saveFields({ description: taskDescription }, 'Error saving task description:')) {
      setOriginalTaskDescription(taskDescription);
      setEditingTaskDescription(false);
    }
  };

  const cancelEditingTaskDescription = () => {
    setTaskDescription(originalTaskDescription);
    setEditingTaskDescription(false);
  };

  const saveDeadline = async (fields) => {
    const saved = await saveFields(fields, 'Error saving task deadline:');
    if (saved) {
      refreshTasks();
    }
    return saved;
  };

  const handleLabelsChange = (updatedLabels) => {
    const labelsArray = Array.isArray(updatedLabels) ? updatedLabels : [];
    const uniqueLabels = [...new Set(labelsArray)];
    if (uniqueLabels.length !== labelsArray.length) {
      return;
    }

    setTaskLabels(uniqueLabels);
    persistTaskFields({ labels: uniqueLabels }).catch(error => {
      if (error instanceof ConcurrentModificationError) {
        toast.info(t('notifications.taskChangedElsewhere'));
        loadTaskData();
        return;
      }
      console.error('Error updating task labels:', error);
      toast.error(t('notifications.labelsUpdateError'));
    });
  };

  const cancelRemoveUser = useCallback(() => setUserToRemove(null), []);

  const handleRemoveUser = async () => {
    if (!userToRemove) return;

    try {
      await removeUserFromTask(task.id, userToRemove.id);
      await reloadAfterChange();
    } catch (error) {
      console.error('Error removing user:', error);
      toast.error(t('notifications.userRemoveError'));
    } finally {
      setUserToRemove(null);
    }
  };

  if (loading) {
    return createPortal(
      <div className="task-details-overlay">
        <div className="task-details-panel loading">
          <p>{t('board.loading')}</p>
        </div>
      </div>,
      document.body
    );
  }

  const renderHeader = () => (editingTaskTitle ? (
    <div className="title-edit-form">
      <input
        ref={taskTitleInputRef}
        type="text"
        value={taskTitle}
        maxLength={NAME_MAX_LENGTH}
        onChange={(e) => setTaskTitle(e.target.value)}
        className="title-input"
        placeholder={t('taskActions.editTitle')}
      />
      <LengthHint value={taskTitle} max={NAME_MAX_LENGTH} />
      <div className="title-edit-actions">
        <button onClick={saveTaskTitle} className="save-title-btn" disabled={!taskTitle.trim()}>
          {t('taskActions.yes')}
        </button>
        <button onClick={cancelEditingTaskTitle} className="cancel-title-btn">
          {t('taskActions.no')}
        </button>
      </div>
    </div>
  ) : (
    <>
      <h3>{taskTitle || task.title}</h3>
      <div className="panel-actions">
        {!readOnly && (
          <button className="edit-title-btn" onClick={startEditingTaskTitle} title={t('taskActions.editTitle')}>
            <EditIcon />
          </button>
        )}
        <button
          className={`history-timeline-btn ${currentView === 'history' ? 'active' : ''}`}
          onClick={() => setCurrentView('history')}
          title={t('taskDetails.historyAndTimeline')}
        >
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z" />
          </svg>
        </button>
        <button
          className={`parent-child-btn ${currentView === 'relationships' ? 'active' : ''}`}
          onClick={() => setCurrentView('relationships')}
          title={t('taskDetails.parentAndChildTasks')}
        >
          <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 16l-4-4m0 0l4-4m-4 4h18M17 8l4 4m0 0l-4 4m4-4H3" />
          </svg>
        </button>
        <button
          className="close-panel-btn"
          onClick={(event) => {
            event.stopPropagation();
            onClose();
          }}
        >
          ×
        </button>
      </div>
    </>
  ));

  const renderDescription = () => (
    <div className="task-description-section">
      <div className="description-header">
        <h4>{t('taskActions.description')}:</h4>
        {!editingTaskDescription && !readOnly && (
          <button
            onClick={startEditingTaskDescription}
            className="edit-description-btn"
            title={t('taskActions.editTaskDescription')}
          >
            <EditIcon />
          </button>
        )}
      </div>

      {editingTaskDescription ? (
        <div className="description-edit-form">
          <textarea
            ref={taskDescriptionInputRef}
            value={taskDescription}
            maxLength={DESCRIPTION_MAX_LENGTH}
            onChange={(e) => setTaskDescription(e.target.value)}
            placeholder={t('taskActions.description')}
            className="description-textarea"
            rows={4}
          ></textarea>
          <LengthHint value={taskDescription} max={DESCRIPTION_MAX_LENGTH} />
          <div className="description-edit-actions">
            <button onClick={saveTaskDescription} className="save-description-btn">
              {t('taskActions.save')}
            </button>
            <button onClick={cancelEditingTaskDescription} className="cancel-edit-btn">
              {t('taskActions.cancel')}
            </button>
          </div>
        </div>
      ) : (
        <div className="description-display">
          {taskDescription ? (
            <p className="description-content">{taskDescription}</p>
          ) : (
            <p className="empty-description">{t('taskActions.noDescription')}</p>
          )}
        </div>
      )}
    </div>
  );

  const renderView = () => {
    if (currentView === 'relationships') {
      return (
        <div className="relationships-view">
          <TaskAssignees
            taskId={task.id}
            users={users}
            assignedUsers={assignedUsers}
            readOnly={readOnly}
            onRequestRemove={setUserToRemove}
            onChanged={reloadAfterChange}
          />
          <TaskRelations
            taskId={task.id}
            parentTaskId={parentTaskId}
            readOnly={readOnly}
            onChanged={reloadAfterChange}
          />
        </div>
      );
    }
    if (currentView === 'history') {
      return (
        <div className="history-timeline-view">
          <TaskDeadline deadline={task.deadline} readOnly={readOnly} onSave={saveDeadline} />
          <TaskColumnHistory taskId={task.id} />
        </div>
      );
    }
    return (
      <>
        {renderDescription()}
        <TaskSubtasks
          taskId={task.id}
          readOnly={readOnly}
          onSubtaskUpdate={onSubtaskUpdate}
          onSaved={flashSaved}
        />
        <TaskAttachments taskId={task.id} readOnly={readOnly} />
        <TaskComments taskId={task.id} />
      </>
    );
  };

  return createPortal(
    <>
      <div
        className="task-details-overlay"
        onClick={(event) => {
          event.stopPropagation();
          onClose();
        }}
      />
      <div className="task-details-panel" ref={panelRef}>
        <div className="panel-header">
          {renderHeader()}
        </div>

        <div className="task-details-main">
          {renderView()}

          {userToRemove && (
            <ConfirmDialog
              title={t('taskActions.confirmDeleteAssignedUser')}
              subject={userToRemove.name}
              onConfirm={handleRemoveUser}
              onCancel={cancelRemoveUser}
            />
          )}
        </div>

        <div className="task-labels-section">
          <h4>{t('board.labels')}</h4>
          <TaskLabels
            taskId={task.id}
            initialLabels={taskLabels}
            onLabelsChange={handleLabelsChange}
            readOnly={readOnly}
          />
        </div>

        <AssignedUsersBar assignedUsers={assignedUsers} readOnly={readOnly} onRequestRemove={setUserToRemove} />

        {success && (
          <div className="success-message">
            {t('notifications.taskUpdated')}
          </div>
        )}
      </div>
    </>,
    document.body
  );
}

export default TaskDetails;
