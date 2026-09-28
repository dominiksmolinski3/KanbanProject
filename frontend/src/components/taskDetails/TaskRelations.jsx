import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import { assignParentTask, fetchTask, fetchTasks, getChildTasks, removeParentTask } from '../../services/api';

function TaskRelations({ taskId, parentTaskId, readOnly, onChanged }) {
  const { t } = useTranslation();
  const [parentTask, setParentTask] = useState(null);
  const [childTasks, setChildTasks] = useState([]);
  const [availableTasks, setAvailableTasks] = useState([]);
  const [showParentSelector, setShowParentSelector] = useState(false);
  const [selectedParentId, setSelectedParentId] = useState('');

  useEffect(() => {
    let live = true;
    (async () => {
      if (!parentTaskId) {
        setParentTask(null);
        return;
      }
      try {
        const data = await fetchTask(parentTaskId);
        if (live) setParentTask(data);
      } catch (error) {
        console.error('Error fetching parent task:', error);
        if (live) setParentTask(null);
      }
    })();
    return () => {
      live = false;
    };
  }, [parentTaskId]);

  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const data = await getChildTasks(taskId);
        if (live) setChildTasks(data || []);
      } catch (error) {
        console.error('Error fetching child tasks:', error);
        if (live) setChildTasks([]);
      }
    })();
    return () => {
      live = false;
    };
  }, [taskId]);

  const handleShowParentSelector = async () => {
    try {
      const allTasks = await fetchTasks();
      const invalidIds = new Set([taskId, ...childTasks.map(child => child.id)]);
      setAvailableTasks(allTasks.filter(candidate => !invalidIds.has(candidate.id)));
      setShowParentSelector(true);
    } catch (error) {
      console.error('Error fetching available parent tasks:', error);
      toast.warning(t('notifications.parentTasksLoadError'));
    }
  };

  const handleAssignParent = async () => {
    if (!selectedParentId) return;

    try {
      await assignParentTask(taskId, parseInt(selectedParentId));
      setSelectedParentId('');
      setShowParentSelector(false);
      await onChanged();
    } catch (error) {
      console.error('Error assigning parent task:', error);
      toast.error(t('notifications.parentTaskAssignError'));
    }
  };

  const handleRemoveParent = async () => {
    try {
      await removeParentTask(taskId);
      await onChanged();
    } catch (error) {
      console.error('Error removing parent task:', error);
      toast.error(t('notifications.parentTaskRemoveError'));
    }
  };

  return (
    <>
      <div className="parent-task-section">
        <div className="section-header">
          <span className="parent-icon">
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor" width="18" height="18">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M7 11l5-5m0 0l5 5m-5-5v12" />
            </svg>
          </span>
          <h4>{t('taskDetails.parentTask')}</h4>
        </div>

        {parentTask ? (
          <div className="current-parent-card">
            <div className="parent-info">
              <strong>{parentTask.title}</strong>
              <span className="parent-id">{t('taskDetails.idLabel')} {parentTask.id}</span>
            </div>
            {!readOnly && (
              <button
                onClick={handleRemoveParent}
                className="remove-parent-btn"
                title={t('taskDetails.removeParentLink')}
              >
                <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor" width="16" height="16">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            )}
          </div>
        ) : (
          <div className="no-parent-card">
            <span>{t('taskDetails.noParentTask')}</span>
          </div>
        )}

        {!readOnly && (
          <button className="manage-parent-btn" onClick={handleShowParentSelector}>
            {parentTask ? t('taskDetails.changeParentTask') : t('taskDetails.assignParentTask')}
          </button>
        )}

        {showParentSelector && !readOnly && (
          <div className="parent-selector-card">
            <h5>{t('taskDetails.selectParentTask')}</h5>
            <select
              value={selectedParentId}
              onChange={(e) => setSelectedParentId(e.target.value)}
              className="parent-select"
            >
              <option value="">{t('taskDetails.chooseTask')}</option>
              {availableTasks.map(availableTask => (
                <option key={availableTask.id} value={availableTask.id}>
                  {availableTask.title}
                </option>
              ))}
            </select>
            <div className="parent-selector-actions">
              <button
                onClick={handleAssignParent}
                disabled={!selectedParentId}
                className="confirm-parent-btn"
              >
                {t('taskActions.assign')}
              </button>
              <button onClick={() => setShowParentSelector(false)} className="cancel-parent-btn">
                {t('taskActions.cancel')}
              </button>
            </div>
          </div>
        )}
      </div>

      <div className="child-tasks-section">
        <div className="section-header">
          <span className="child-icon">
            <svg xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24" stroke="currentColor" width="18" height="18">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 13l-5 5m0 0l-5-5m5 5V6" />
            </svg>
          </span>
          <h4>{t('taskDetails.childTasks')}</h4>
        </div>

        {childTasks.length > 0 ? (
          <div className="child-tasks-grid">
            {childTasks.map(childTask => (
              <div key={childTask.id} className="child-task-card">
                <div className="child-task-info">
                  <strong>{childTask.title}</strong>
                  <span className="child-id">{t('taskDetails.idLabel')} {childTask.id}</span>
                  {childTask.status && (
                    <span className={`status-badge ${childTask.status.toLowerCase()}`}>
                      {childTask.status}
                    </span>
                  )}
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="no-children-card">
            <span>{t('taskDetails.noChildTasks')}</span>
          </div>
        )}
      </div>
    </>
  );
}

export default TaskRelations;
