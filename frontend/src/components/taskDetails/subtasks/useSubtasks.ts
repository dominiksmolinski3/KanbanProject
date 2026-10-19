import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import { addSubTask, deleteSubTask, fetchSubTasksByTaskId, toggleSubTaskCompletion, updateSubTask } from '../../../services/api';
import type { Id, SubTask } from '../../../types/api';

export function useSubtasks(taskId: Id, onSubtaskUpdate: (() => void) | undefined, onSaved: () => void) {
  const { t } = useTranslation();
  const [subtasks, setSubtasks] = useState<SubTask[]>([]);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
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
    load();
    window.addEventListener('task-subtasks-changed', load);
    return () => window.removeEventListener('task-subtasks-changed', load);
  }, [load]);

  const add = async (title: string): Promise<boolean> => {
    try {
      await addSubTask(taskId, title);
      await load();
      onSubtaskUpdate?.();
      onSaved();
      return true;
    } catch (error) {
      console.error('Error adding subtask:', error);
      toast.error(t('notifications.subtaskAddError'));
      return false;
    }
  };

  const toggle = async (subtaskId: Id) => {
    try {
      await toggleSubTaskCompletion(subtaskId);
      setSubtasks(prev => prev.map(subtask => (
        subtask.id === subtaskId ? { ...subtask, completed: !subtask.completed } : subtask
      )));
      onSubtaskUpdate?.();
    } catch (error) {
      console.error('Error toggling subtask completion:', error);
      toast.error(t('notifications.subtaskToggleError'));
    }
  };

  const remove = async (subtaskId: Id) => {
    try {
      await deleteSubTask(subtaskId);
      setSubtasks(prev => prev.filter(subtask => subtask.id !== subtaskId));
      onSubtaskUpdate?.();
      onSaved();
    } catch (error) {
      console.error('Error deleting subtask:', error);
      toast.error(t('notifications.subtaskDeleteError'));
    }
  };

  const saveDescription = async (subtaskId: Id, description: string): Promise<boolean> => {
    try {
      await updateSubTask(subtaskId, { description });
      setSubtasks(prev => prev.map(subtask => (
        subtask.id === subtaskId ? { ...subtask, description } : subtask
      )));
      onSaved();
      return true;
    } catch (error) {
      console.error('Error saving subtask description:', error);
      toast.error(t('notifications.subtaskDescriptionError'));
      return false;
    }
  };

  return { subtasks, loaded, add, toggle, remove, saveDescription };
}
