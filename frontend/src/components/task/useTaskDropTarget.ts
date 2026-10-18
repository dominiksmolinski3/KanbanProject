import { useEffect, useState, type DragEvent } from 'react';
import { toast } from 'react-toastify';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import { assignUserToTask, WipLimitExceededError } from '../../services/api';
import type { Task } from '../../types/api';

const ASSIGNMENT_ERROR_VISIBLE_MS = 5000;

export function useTaskDropTarget(task: Task, dismissWarning: () => void) {
  const { dragAndDrop, readOnly, refreshTasks } = useKanban();
  const { t } = useTranslation();
  const [isDragOver, setIsDragOver] = useState(false);
  const [assignmentError, setAssignmentError] = useState<string | null>(null);

  useEffect(() => {
    if (assignmentError) {
      const timer = setTimeout(() => {
        setAssignmentError(null);
      }, ASSIGNMENT_ERROR_VISIBLE_MS);

      return () => clearTimeout(timer);
    }
  }, [assignmentError]);

  const onDragOver = (e: DragEvent<Element>) => {
    e.preventDefault();
    e.stopPropagation();

    if (e.dataTransfer.types.includes('application/task') ||
        e.dataTransfer.types.includes('application/user')) {
      setIsDragOver(true);
    }
  };

  const onDragLeave = (e: DragEvent<Element>) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);
  };

  const dropUser = async (dataString: string) => {
    try {
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
  };

  const onDrop = async (e: DragEvent<Element>) => {
    e.preventDefault();
    e.stopPropagation();
    setIsDragOver(false);
    if (readOnly) {
      return;
    }

    dismissWarning();

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
      await dropUser(e.dataTransfer.getData('application/user'));
    }
  };

  return { isDragOver, assignmentError, onDragOver, onDragLeave, onDrop };
}
