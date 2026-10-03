import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Icon from '../Icon';
import { toast } from 'react-toastify';
import { assignParentTask, fetchTask, fetchTasks, getChildTasks, removeParentTask } from '../../services/api';

function TaskRelations({ taskId, parentTaskId, readOnly, onChanged, part }) {
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

  const parentPart = (
    <div className="parent-task-section">
      {parentTask ? (
        <div className="current-parent-card">
          <span className="parent-info">{parentTask.title}</span>
          {!readOnly && (
            <button
              type="button"
              onClick={handleRemoveParent}
              className="remove-parent-btn"
              aria-label={t('taskDetails.removeParentLink')}
              title={t('taskDetails.removeParentLink')}
            >
              <Icon name="close" size="sm" />
            </button>
          )}
        </div>
      ) : (
        <span className="no-parent-card">{t('taskDetails.noParentTask')}</span>
      )}

      {!readOnly && !showParentSelector && (parentTask ? (
        <button
          type="button"
          className="edit-parent-btn"
          onClick={handleShowParentSelector}
          aria-label={t('taskDetails.changeParentTask')}
          title={t('taskDetails.changeParentTask')}
        >
          <Icon name="edit" size="sm" />
        </button>
      ) : (
        <button type="button" className="manage-parent-btn" onClick={handleShowParentSelector}>
          {t('taskDetails.assignParentTask')}
        </button>
      ))}

      {showParentSelector && !readOnly && (
        <div className="parent-selector-card">
          <select
            value={selectedParentId}
            onChange={(e) => setSelectedParentId(e.target.value)}
            className="parent-select"
            aria-label={t('taskDetails.selectParentTask')}
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
              type="button"
              onClick={handleAssignParent}
              disabled={!selectedParentId}
              className="confirm-parent-btn"
            >
              {t('taskActions.assign')}
            </button>
            <button type="button" onClick={() => setShowParentSelector(false)} className="cancel-parent-btn">
              {t('taskActions.cancel')}
            </button>
          </div>
        </div>
      )}
    </div>
  );

  const childrenPart = (
    <section className="child-tasks-section">
      <div className="section-header">
        <h3>{t('taskDetails.childTasks')}</h3>
        {childTasks.length > 0 && <span className="section-meta">{childTasks.length}</span>}
      </div>

      {childTasks.length > 0 ? (
        <ul className="child-tasks-grid">
          {childTasks.map(childTask => (
            <li key={childTask.id} className="child-task-card">
              <span className="child-task-info">{childTask.title}</span>
              {childTask.status && (
                <span className={`status-badge ${childTask.status.toLowerCase()}`}>
                  {childTask.status}
                </span>
              )}
            </li>
          ))}
        </ul>
      ) : (
        <p className="no-children-card">{t('taskDetails.noChildTasks')}</p>
      )}
    </section>
  );

  if (part === 'parent') return parentPart;
  if (part === 'children') return childrenPart;
  return (
    <>
      {parentPart}
      {childrenPart}
    </>
  );
}

export default TaskRelations;
