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
import TaskAssignees from './taskDetails/TaskAssignees';
import TaskRelations from './taskDetails/TaskRelations';
import TaskDeadline from './taskDetails/TaskDeadline';
import TaskColumnHistory from './taskDetails/TaskColumnHistory';
import ConfirmDialog from './taskDetails/ConfirmDialog';
import EditIcon from './taskDetails/EditIcon';
import Icon from './Icon';
import { splitPriority } from '../board/cardModel';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';

const SAVED_NOTICE_MS = 3000;

function TaskDetails({ task, onClose, onSubtaskUpdate }) {
  const { refreshTasks, readOnly, columns = [], rows = [], setDailyFocus, updateTaskCompletion } = useKanban();
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
  const [currentView, setCurrentView] = useState('details');

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

  const handleLabelsChange = (updatedLabels, updatedTask) => {
    setTaskLabels(Array.isArray(updatedLabels) ? updatedLabels : []);
    if (updatedTask && typeof updatedTask.version === 'number') {
      setTaskVersion(updatedTask.version);
    }
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
      <>
        <div className="task-details-overlay" />
        <section className="task-details-panel loading" aria-busy="true">
          <p>{t('board.loading')}</p>
        </section>
      </>,
      document.body
    );
  }

  const title = taskTitle || task.title;
  const titleId = `task-sheet-title-${task.id}`;
  const columnName = columns.find((column) => String(column.id) === String(task.columnId))?.name;
  const rowName = rows.find((row) => String(row.id) === String(task.rowId))?.name;
  const { priority } = splitPriority(taskLabels);

  const renderTitle = () => (editingTaskTitle ? (
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
          {t('taskActions.save')}
        </button>
        <button onClick={cancelEditingTaskTitle} className="cancel-title-btn">
          {t('taskActions.cancel')}
        </button>
      </div>
    </div>
  ) : (
    <div className="sheet-titlerow">
      <input
        type="checkbox"
        className="sheet-check"
        checked={Boolean(task.completed)}
        disabled={readOnly || !updateTaskCompletion}
        onChange={() => updateTaskCompletion?.(task.id, !task.completed)}
        aria-label={task.completed ? t('taskActions.reopen') : t('taskActions.complete')}
        title={task.completed ? t('taskActions.reopen') : t('taskActions.complete')}
      />
      <h2 className="sheet-title" id={titleId}>{title}</h2>
      <div className="panel-actions">
        {setDailyFocus && (
          <button
            type="button"
            className={`daily-focus-toggle${task.dailyFocus ? ' active' : ''}`}
            aria-pressed={Boolean(task.dailyFocus)}
            onClick={() => setDailyFocus(task.id, !task.dailyFocus)}
            aria-label={task.dailyFocus ? t('taskActions.removeFromDailyFocus') : t('taskActions.addToDailyFocus')}
            title={task.dailyFocus ? t('taskActions.removeFromDailyFocus') : t('taskActions.addToDailyFocus')}
          >
            <Icon name="star" filled={Boolean(task.dailyFocus)} />
          </button>
        )}
        {!readOnly && (
          <button
            type="button"
            className="edit-title-btn"
            onClick={startEditingTaskTitle}
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
  ));

  const renderDescription = () => (
    <section className="task-description-section">
      <div className="description-header">
        <h3>{t('taskActions.description')}</h3>
        {!editingTaskDescription && !readOnly && (
          <button
            onClick={startEditingTaskDescription}
            className="edit-description-btn"
            aria-label={t('taskActions.editTaskDescription')}
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
            rows={5}
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
    </section>
  );

  const renderProps = () => (
    <dl className="sheet-props">
      <div className="sheet-prop">
        <dt><Icon name="people" size="sm" />{t('taskDetails.props.assignees')}</dt>
        <dd>
          <TaskAssignees
            taskId={task.id}
            users={users}
            assignedUsers={assignedUsers}
            readOnly={readOnly}
            onRequestRemove={setUserToRemove}
            onChanged={reloadAfterChange}
          />
        </dd>
      </div>
      <div className="sheet-prop">
        <dt><Icon name="filter" size="sm" />{t('board.labels')}</dt>
        <dd>
          <TaskLabels
            taskId={task.id}
            initialLabels={taskLabels}
            onLabelsChange={handleLabelsChange}
            readOnly={readOnly}
          />
        </dd>
      </div>
      <div className="sheet-prop">
        <dt><Icon name="priority" size="sm" />{t('taskDetails.props.priority')}</dt>
        <dd>
          {priority ? (
            <span className={`task-priority-pill priority-${priority}`}>
              <span className="priority-bars" aria-hidden="true"><i /><i /><i /><i /></span>
              {t(`taskActions.priority.${priority}`)}
            </span>
          ) : (
            <span className="sheet-prop-empty" aria-hidden="true">–</span>
          )}
        </dd>
      </div>
      <div className="sheet-prop">
        <dt><Icon name="calendar" size="sm" />{t('taskActions.deadline')}</dt>
        <dd>
          <TaskDeadline deadline={task.deadline} readOnly={readOnly} onSave={saveDeadline} />
        </dd>
      </div>
      <div className="sheet-prop">
        <dt><Icon name="link" size="sm" />{t('taskDetails.parentTask')}</dt>
        <dd>
          <TaskRelations
            part="parent"
            taskId={task.id}
            parentTaskId={parentTaskId}
            readOnly={readOnly}
            onChanged={reloadAfterChange}
          />
        </dd>
      </div>
    </dl>
  );

  const tabs = [
    { id: 'details', label: t('taskDetails.tabs.details') },
    { id: 'comments', label: t('taskComments.heading'), icon: 'comment' },
    { id: 'history', label: t('taskDetails.historyAndTimeline') },
  ];

  const renderView = () => {
    if (currentView === 'comments') {
      return <TaskComments taskId={task.id} />;
    }
    if (currentView === 'history') {
      return <TaskColumnHistory taskId={task.id} />;
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
        <TaskRelations
          part="children"
          taskId={task.id}
          parentTaskId={parentTaskId}
          readOnly={readOnly}
          onChanged={reloadAfterChange}
        />
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
      <section
        className="task-details-panel"
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        <div className="panel-header">
          {(columnName || rowName) && (
            <div className="sheet-crumbs">
              {columnName && <span>{columnName}</span>}
              {columnName && rowName && <Icon name="chevron-right" size="sm" />}
              {rowName && <span>{rowName}</span>}
            </div>
          )}
          {renderTitle()}
          {renderProps()}
          <div className="sheet-tabs" role="tablist" aria-label={title}>
            {tabs.map((tab) => (
              <button
                key={tab.id}
                type="button"
                role="tab"
                id={`sheet-tab-${tab.id}`}
                aria-selected={currentView === tab.id}
                aria-controls="sheet-body"
                className="sheet-tab"
                onClick={() => setCurrentView(tab.id)}
              >
                {tab.icon && <Icon name={tab.icon} size="sm" />}
                {tab.label}
              </button>
            ))}
          </div>
        </div>

        <div
          className="task-details-main"
          id="sheet-body"
          role="tabpanel"
          aria-labelledby={`sheet-tab-${currentView}`}
        >
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

        {success && (
          <div className="success-message" role="status">
            <Icon name="check" size="sm" />
            {t('notifications.taskUpdated')}
          </div>
        )}
      </section>
    </>,
    document.body
  );
}

export default TaskDetails;
