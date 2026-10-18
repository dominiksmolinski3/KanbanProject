import { useCallback, useEffect, useRef, useState } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import { reportError } from '../../context/kanban/reportError';
import { fetchUsers, fetchTask, removeUserFromTask, updateTask, ConcurrentModificationError } from '../../services/api';
import type { Id, Task, TaskPatch, User } from '../../types/api';
import { useEditableField } from './useEditableField';
import { useSavedNotice } from './useSavedNotice';

export function useTaskDetails(task: Task) {
  const { refreshTasks } = useKanban();
  const { t } = useTranslation();
  const { saved, flashSaved } = useSavedNotice();
  const [loading, setLoading] = useState(true);
  const [users, setUsers] = useState<User[]>([]);
  const [assignedUsers, setAssignedUsers] = useState<User[]>([]);
  const [userToRemove, setUserToRemove] = useState<User | null>(null);
  const [parentTaskId, setParentTaskId] = useState<Id | null>(null);
  const [labels, setLabels] = useState<string[]>([]);
  const [version, setVersion] = useState<number | null>(null);
  const title = useEditableField<HTMLInputElement>();
  const description = useEditableField<HTMLTextAreaElement>();
  const loadedTaskId = useRef<Id | null>(null);

  const loadTaskData = async () => {
    try {
      if (loadedTaskId.current !== task.id) {
        setLoading(true);
      }
      const taskData = await fetchTask(task.id);
      setVersion(typeof taskData.version === 'number' ? taskData.version : null);
      setParentTaskId(taskData.parentTaskId || null);

      const usersData = (await fetchUsers()) || [];
      const assignedIds = taskData.userIds || [];
      setUsers(usersData);
      setAssignedUsers(usersData.filter(user => assignedIds.includes(user.id)));

      description.load(taskData.description || '');
      title.load(taskData.title);
      setLabels(taskData.labels || []);

      loadedTaskId.current = task.id;
    } catch (error) {
      console.error('Error loading task data:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadTaskData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [task.id]);

  const reloadAfterChange = async () => {
    await loadTaskData();
    refreshTasks();
    flashSaved();
  };

  const persistTaskFields = async (fields: TaskPatch) => {
    const payload = typeof version === 'number' ? { ...fields, version } : fields;
    const updated = await updateTask(task.id, payload);
    if (updated && typeof updated.version === 'number') {
      setVersion(updated.version);
    }
    return updated;
  };

  const reportSaveError = (error: unknown, logLabel: string) => {
    if (error instanceof ConcurrentModificationError) {
      toast.info(t('notifications.taskChangedElsewhere'));
      loadTaskData();
      return;
    }
    console.error(logLabel, error);
    reportError(t, error);
  };

  const saveFields = async (fields: TaskPatch, logLabel: string) => {
    try {
      await persistTaskFields(fields);
      flashSaved();
      return true;
    } catch (error) {
      reportSaveError(error, logLabel);
      return false;
    }
  };

  const saveTitle = async () => {
    if (await saveFields({ title: title.value }, 'Error saving task title:')) {
      title.commit();
      refreshTasks();
    }
  };

  const saveDescription = async () => {
    if (await saveFields({ description: description.value }, 'Error saving task description:')) {
      description.commit();
    }
  };

  const saveDeadline = async (fields: TaskPatch) => {
    const done = await saveFields(fields, 'Error saving task deadline:');
    if (done) {
      refreshTasks();
    }
    return done;
  };

  const changeLabels = (updatedLabels: string[], updatedTask?: Task) => {
    setLabels(Array.isArray(updatedLabels) ? updatedLabels : []);
    if (updatedTask && typeof updatedTask.version === 'number') {
      setVersion(updatedTask.version);
    }
  };

  const cancelRemoveUser = useCallback(() => setUserToRemove(null), []);

  const removeUser = async () => {
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

  return {
    loading, saved, flashSaved,
    users, assignedUsers, parentTaskId, labels,
    title, description,
    userToRemove, setUserToRemove, cancelRemoveUser, removeUser,
    reloadAfterChange, saveTitle, saveDescription, saveDeadline, changeLabels,
  };
}

export type TaskDetailsState = ReturnType<typeof useTaskDetails>;
