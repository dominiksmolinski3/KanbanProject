import { useEffect, useRef, useState, type DragEvent, type MouseEvent, type RefObject } from 'react';
import { useKanban } from '../../context/KanbanContext';
import type { Id, Task } from '../../types/api';
import { setTiltedDragImage } from './dragImage';

const WARNING_VISIBLE_MS = 5000;

export function useTaskDragSource(task: Task, columnId: Id, taskRef: RefObject<HTMLElement | null>) {
  const { readOnly, setDraggedTaskId } = useKanban();
  const [isDragging, setIsDragging] = useState(false);
  const [showWarning, setShowWarning] = useState(false);
  const warningTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const hasUnfinishedSubtasks = (task.openSubtasks ?? 0) > 0;

  useEffect(() => () => {
    if (warningTimeoutRef.current) {
      clearTimeout(warningTimeoutRef.current);
    }
  }, []);

  const dismissWarning = () => {
    setShowWarning(false);
    if (warningTimeoutRef.current) {
      clearTimeout(warningTimeoutRef.current);
    }
  };

  const onDragStart = (e: DragEvent<Element>) => {
    if (readOnly) {
      e.preventDefault();
      return;
    }
    const data = {
      id: task.id,
      type: 'task',
      sourceColumnId: columnId,
      sourceRowId: task.rowId,
    };

    e.dataTransfer.setData('application/task', JSON.stringify(data));
    e.dataTransfer.setData('taskId', String(task.id));
    e.dataTransfer.setData('columnId', String(columnId));
    e.dataTransfer.effectAllowed = 'move';
    setTiltedDragImage(e, taskRef.current);

    if (taskRef.current) {
      taskRef.current.classList.add('dragging');
    }

    setIsDragging(true);
    setDraggedTaskId?.(task.id);
    document.body.classList.add('showing-task-relationships');

    if (hasUnfinishedSubtasks) {
      setShowWarning(true);

      if (warningTimeoutRef.current) {
        clearTimeout(warningTimeoutRef.current);
      }

      warningTimeoutRef.current = setTimeout(() => {
        setShowWarning(false);
      }, WARNING_VISIBLE_MS);
    }
  };

  const onDragEnd = () => {
    dismissWarning();
    if (taskRef.current) {
      taskRef.current.classList.remove('dragging');
    }
    setIsDragging(false);
    setDraggedTaskId?.(null);
    document.body.classList.remove('showing-task-relationships');
  };

  const closeWarning = (e: MouseEvent) => {
    e.stopPropagation();
    dismissWarning();
  };

  return { isDragging, showWarning: hasUnfinishedSubtasks && showWarning, dismissWarning, closeWarning, onDragStart, onDragEnd };
}
