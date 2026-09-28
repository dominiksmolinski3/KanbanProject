import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import { addSubTask, deleteSubTask, fetchSubTask, fetchSubTasksByTaskId, toggleSubTaskCompletion, updateSubTask } from '../../services/api';
import ConfirmDialog from './ConfirmDialog';
import EditIcon from './EditIcon';

function TaskSubtasks({ taskId, readOnly, onSubtaskUpdate, onSaved }) {
  const { t } = useTranslation();
  const [subtasks, setSubtasks] = useState([]);
  const [loaded, setLoaded] = useState(false);
  const [newSubtaskTitle, setNewSubtaskTitle] = useState('');
  const [expandedSubtaskId, setExpandedSubtaskId] = useState(null);
  const [editingDescription, setEditingDescription] = useState(false);
  const [subtaskDescription, setSubtaskDescription] = useState('');
  const [subtaskToDelete, setSubtaskToDelete] = useState(null);
  const descriptionInputRef = useRef(null);

  const loadSubtasks = useCallback(async () => {
    try {
      const data = await fetchSubTasksByTaskId(taskId);
      setSubtasks(data || []);
    } catch (error) {
      console.error('Error refreshing subtasks:', error);
    } finally {
      setLoaded(true);
    }
  }, [taskId]);

  useEffect(() => {
    loadSubtasks();
    window.addEventListener('task-subtasks-changed', loadSubtasks);
    return () => window.removeEventListener('task-subtasks-changed', loadSubtasks);
  }, [loadSubtasks]);

  const notifyChanged = () => {
    if (onSubtaskUpdate) {
      onSubtaskUpdate();
    }
  };

  const handleAddSubtask = async () => {
    if (!newSubtaskTitle.trim()) return;

    try {
      await addSubTask(taskId, newSubtaskTitle.trim());
      setNewSubtaskTitle('');
      await loadSubtasks();
      notifyChanged();
      onSaved();
    } catch (error) {
      console.error('Error adding subtask:', error);
      toast.error(t('notifications.subtaskAddError'));
    }
  };

  const handleToggleSubtask = async (subtaskId) => {
    try {
      await toggleSubTaskCompletion(subtaskId);
      setSubtasks(prev => prev.map(subtask => (
        subtask.id === subtaskId ? { ...subtask, completed: !subtask.completed } : subtask
      )));
      notifyChanged();
    } catch (error) {
      console.error('Error toggling subtask completion:', error);
      toast.error(t('notifications.subtaskToggleError'));
    }
  };

  const cancelDelete = useCallback(() => setSubtaskToDelete(null), []);

  const handleDeleteSubtask = async () => {
    if (!subtaskToDelete) return;

    try {
      await deleteSubTask(subtaskToDelete.id);
      setSubtasks(prev => prev.filter(subtask => subtask.id !== subtaskToDelete.id));
      notifyChanged();
      onSaved();
    } catch (error) {
      console.error('Error deleting subtask:', error);
      toast.error(t('notifications.subtaskDeleteError'));
    } finally {
      setSubtaskToDelete(null);
    }
  };

  const toggleSubtaskExpansion = async (subtaskId) => {
    setEditingDescription(false);
    if (expandedSubtaskId === subtaskId) {
      setExpandedSubtaskId(null);
      return;
    }

    setExpandedSubtaskId(subtaskId);
    try {
      const subtaskData = await fetchSubTask(subtaskId);
      setSubtaskDescription(subtaskData.description || '');
    } catch (error) {
      console.error('Error fetching subtask description:', error);
      setSubtaskDescription('');
    }
  };

  const expandedSubtask = () => subtasks.find(s => s.id === expandedSubtaskId);

  const startEditingDescription = () => {
    if (!expandedSubtaskId) return;
    const current = expandedSubtask();
    if (current) {
      setSubtaskDescription(current.description || '');
    }
    setEditingDescription(true);
    setTimeout(() => descriptionInputRef.current?.focus(), 0);
  };

  const saveSubtaskDescription = async () => {
    if (!expandedSubtaskId) return;

    try {
      await updateSubTask(expandedSubtaskId, { description: subtaskDescription });
      setSubtasks(prev => prev.map(subtask => (
        subtask.id === expandedSubtaskId ? { ...subtask, description: subtaskDescription } : subtask
      )));
      setEditingDescription(false);
      onSaved();
    } catch (error) {
      console.error('Error saving subtask description:', error);
      toast.error(t('notifications.subtaskDescriptionError'));
    }
  };

  const cancelEditingDescription = () => {
    setEditingDescription(false);
    const current = expandedSubtask();
    if (current) {
      setSubtaskDescription(current.description || '');
    }
  };

  const renderDescription = () => (
    <div className="subtask-description-container">
      {editingDescription ? (
        <div className="description-edit-form">
          <textarea
            ref={descriptionInputRef}
            value={subtaskDescription}
            onChange={(e) => setSubtaskDescription(e.target.value)}
            placeholder={t('taskActions.description')}
            className="description-textarea"
            rows={4}
          ></textarea>
          <div className="description-edit-actions">
            <button onClick={saveSubtaskDescription} className="save-description-btn">
              {t('taskActions.save')}
            </button>
            <button onClick={cancelEditingDescription} className="cancel-edit-btn">
              {t('taskActions.cancel')}
            </button>
          </div>
        </div>
      ) : (
        <div className="description-display">
          <div className="description-header">
            <h5>{t('taskActions.description')}:</h5>
            {!readOnly && (
              <button
                onClick={startEditingDescription}
                className="edit-description-btn"
                title={t('taskActions.editSubTaskDescription')}
              >
                <EditIcon />
              </button>
            )}
          </div>
          {subtaskDescription ? (
            <p className="description-content">{subtaskDescription}</p>
          ) : (
            <p className="empty-description">{t('taskActions.noDescription')}</p>
          )}
        </div>
      )}
    </div>
  );

  return (
    <div className="subtasks-section">
      <h4>{t('taskActions.subtasks')}</h4>

      {!readOnly && (
        <div className="add-subtask-form">
          <input
            type="text"
            value={newSubtaskTitle}
            onChange={(e) => setNewSubtaskTitle(e.target.value)}
            placeholder={t('taskActions.shadowDescription')}
            className="subtask-input"
          />
          <button
            onClick={handleAddSubtask}
            disabled={!newSubtaskTitle.trim()}
            className="add-subtask-btn"
          >
            {t('header.addTask')}
          </button>
        </div>
      )}

      {subtasks.length > 0 ? (
        <div className="subtasks-list">
          {subtasks.map(subtask => {
            const expanded = expandedSubtaskId === subtask.id;
            const toggleLabel = expanded ? t('taskActions.hideDetails') : t('taskActions.showDetails');
            return (
              <div key={subtask.id} className={`subtask-item ${expanded ? 'expanded' : ''}`}>
                <div className="subtask-header">
                  <input
                    type="checkbox"
                    checked={subtask.completed}
                    onChange={() => handleToggleSubtask(subtask.id)}
                    disabled={readOnly}
                    id={`subtask-${subtask.id}`}
                    className="subtask-checkbox"
                  />
                  <label
                    htmlFor={`subtask-${subtask.id}`}
                    className={subtask.completed ? 'completed' : ''}
                  >
                    {subtask.title}
                  </label>

                  <div className="subtask-actions">
                    <button
                      className="description-toggle-btn dark-bg-with-text"
                      onClick={() => toggleSubtaskExpansion(subtask.id)}
                      title={toggleLabel}
                    >
                      <span className={expanded ? 'arrow-icon rotated' : 'arrow-icon'}>▼</span>
                      <span className="button-text">{toggleLabel}</span>
                    </button>
                    {!readOnly && (
                      <button
                        className="delete-subtask-btn"
                        onClick={() => setSubtaskToDelete(subtask)}
                        title={t('taskActions.deleteSubTask')}
                      >
                        ×
                      </button>
                    )}
                  </div>
                </div>

                {expanded && renderDescription()}
              </div>
            );
          })}
        </div>
      ) : loaded && (
        <p className="no-subtasks">{t('taskActions.noSubtasks')}</p>
      )}

      {subtaskToDelete && (
        <ConfirmDialog
          title={t('taskActions.confirmDeleteSubTask')}
          subject={subtaskToDelete.title}
          onConfirm={handleDeleteSubtask}
          onCancel={cancelDelete}
        />
      )}
    </div>
  );
}

export default TaskSubtasks;
