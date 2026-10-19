import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import { addSubTask, deleteSubTask, fetchSubTasksByTaskId, toggleSubTaskCompletion, updateSubTask } from '../../../services/api';
import { logged } from '../../../services/logged';
import { taskKeys } from '../../../services/taskQueries';
import type { Id, SubTask } from '../../../types/api';

const NONE: SubTask[] = [];

export function useSubtasks(taskId: Id, onSubtaskUpdate: (() => void) | undefined, onSaved: () => void) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const key = taskKeys.subtasks(taskId);

  const query = useQuery({
    queryKey: key,
    queryFn: async () => (await logged('Error refreshing subtasks:', () => fetchSubTasksByTaskId(taskId))) || [],
  });

  const edit = (change: (subtasks: SubTask[]) => SubTask[]) =>
    queryClient.setQueryData<SubTask[]>(key, previous => change(previous ?? NONE));

  const add = async (title: string): Promise<boolean> => {
    try {
      await addSubTask(taskId, title);
      await queryClient.invalidateQueries({ queryKey: key });
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
      edit(subtasks => subtasks.map(subtask => (
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
      edit(subtasks => subtasks.filter(subtask => subtask.id !== subtaskId));
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
      edit(subtasks => subtasks.map(subtask => (
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

  return { subtasks: query.data ?? NONE, loaded: !query.isPending, add, toggle, remove, saveDescription };
}
