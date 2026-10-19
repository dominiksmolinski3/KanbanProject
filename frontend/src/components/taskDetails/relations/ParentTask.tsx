import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { toast } from 'react-toastify';
import { assignParentTask, fetchTask, fetchTasks, getChildTasks, removeParentTask } from '../../../services/api';
import { logged } from '../../../services/logged';
import { taskKeys } from '../../../services/taskQueries';
import type { Id, Task } from '../../../types/api';
import Icon from '../../Icon';
import ParentSelector from './ParentSelector';

interface ParentTaskProps {
  taskId: Id;
  parentTaskId: Id | null;
  readOnly: boolean;
  onChanged: () => void | Promise<void>;
}

export default function ParentTask({ taskId, parentTaskId, readOnly, onChanged }: ParentTaskProps) {
  const { t } = useTranslation();
  const [candidates, setCandidates] = useState<Task[]>([]);
  const [selecting, setSelecting] = useState(false);

  const parentQuery = useQuery({
    queryKey: taskKeys.task(parentTaskId ?? 0),
    enabled: Boolean(parentTaskId),
    queryFn: () => logged('Error fetching parent task:', () => fetchTask(parentTaskId as Id)),
  });
  const parent: Task | null = parentTaskId ? parentQuery.data ?? null : null;

  const showSelector = async () => {
    try {
      const [allTasks, children] = await Promise.all([fetchTasks(), getChildTasks(taskId)]);
      const invalidIds = new Set([taskId, ...(children || []).map(child => child.id)]);
      setCandidates(allTasks.filter(candidate => !invalidIds.has(candidate.id)));
      setSelecting(true);
    } catch (error) {
      console.error('Error fetching available parent tasks:', error);
      toast.warning(t('notifications.parentTasksLoadError'));
    }
  };

  const assign = async (parentId: number) => {
    try {
      await assignParentTask(taskId, parentId);
      setSelecting(false);
      await onChanged();
    } catch (error) {
      console.error('Error assigning parent task:', error);
      toast.error(t('notifications.parentTaskAssignError'));
    }
  };

  const removeParent = async () => {
    try {
      await removeParentTask(taskId);
      await onChanged();
    } catch (error) {
      console.error('Error removing parent task:', error);
      toast.error(t('notifications.parentTaskRemoveError'));
    }
  };

  return (
    <div className="parent-task-section">
      {parent ? (
        <div className="current-parent-card">
          <span className="parent-info">{parent.title}</span>
          {!readOnly && (
            <button
              type="button"
              onClick={removeParent}
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

      {!readOnly && !selecting && (parent ? (
        <button
          type="button"
          className="edit-parent-btn"
          onClick={showSelector}
          aria-label={t('taskDetails.changeParentTask')}
          title={t('taskDetails.changeParentTask')}
        >
          <Icon name="edit" size="sm" />
        </button>
      ) : (
        <button type="button" className="manage-parent-btn" onClick={showSelector}>
          {t('taskDetails.assignParentTask')}
        </button>
      ))}

      {selecting && !readOnly && (
        <ParentSelector candidates={candidates} onAssign={assign} onCancel={() => setSelecting(false)} />
      )}
    </div>
  );
}
