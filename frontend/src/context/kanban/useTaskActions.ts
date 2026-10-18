import { toast } from 'react-toastify';
import {
  addTask,
  ConcurrentModificationError,
  deleteTask,
  getUserWipStatus,
  ParentTaskNotCompletedError,
  reorderTasks,
  setTaskDailyFocus,
  updateTaskColumn,
  updateTaskCompletion,
  updateTaskName,
  updateTaskRow,
  updateUserWipLimit,
} from '../../services/api';
import type { Id } from '../../types/api';
import { reportError } from './reportError';
import type { LooseId, SharedActionDeps } from './types';

type TaskActionDeps = Pick<
  SharedActionDeps,
  't' | 'blockIfReadOnly' | 'refuseIfReadOnly' | 'columns' | 'rows' | 'tasks' | 'setTasks' | 'refreshTasks'
>;

export function useTaskActions({
  t, blockIfReadOnly, refuseIfReadOnly,
  columns, rows, tasks, setTasks, refreshTasks,
}: TaskActionDeps) {
  const handleUpdateTaskName = async (taskId: Id, newName: string) => {
    if (blockIfReadOnly()) return false;
    try {
      await updateTaskName(taskId, newName);
      setTasks(previous => previous.map(task =>
        task.id === taskId ? { ...task, title: newName } : task
      ));
      toast.success(t('notifications.taskUpdated'));
      return true;
    } catch (err) {
      console.error('Error updating task name:', err);
      reportError(t, err);
      return false;
    }
  };

  const handleAddTask = async (title: string, columnId?: Id | null, deadline: string | null = null, rowId: Id | null = null) => {
    refuseIfReadOnly();
    if (!columns || columns.length === 0) {
      const errorMessage = t('notifications.noColumnError');
      toast.error(errorMessage);
      throw new Error(errorMessage);
    }
    try {
      const targetColumnId = columnId || columns[0].id;
      const newTask = await addTask(title, targetColumnId, deadline);

      let finalTask = newTask;
      const hasRows = rows && rows.length > 0;
      const validProvidedRow = rowId && (rows?.some(r => String(r.id) === String(rowId)));

      if (validProvidedRow) {
        await updateTaskRow(newTask.id, rowId);
        finalTask = { ...newTask, rowId };
      } else if (hasRows) {
        const targetRowId = rows[0].id;
        await updateTaskRow(newTask.id, targetRowId);
        finalTask = { ...newTask, rowId: targetRowId };
      }

      setTasks(prevTasks => [...prevTasks, finalTask]);

      await refreshTasks();
      toast.success(t('notifications.taskAdded', { title }));
      return newTask;
    } catch (err) {
      reportError(t, err);
      throw err;
    }
  };

  const handleMoveTask = async (taskId: LooseId, newColumnId?: LooseId | null, newRowId?: LooseId | null): Promise<void> => {
    if (blockIfReadOnly()) return;
    try {
      const task = tasks.find(t => t.id === taskId);
      if (!task) return;

      const columnChanged = newColumnId !== undefined && newColumnId !== null && newColumnId !== "null" && newColumnId !== task.columnId;
      const rowChanged = newRowId !== undefined && newRowId !== null && newRowId !== "null" && newRowId !== task.rowId;

      if (!columnChanged && !rowChanged) return;

      let targetColumnName = '';
      let targetRowName = '';

      const updatedTask = { ...task };

      if (columnChanged) {
        const columnId = newColumnId as Id;
        await updateTaskColumn(task.id, columnId);
        updatedTask.columnId = columnId;
        const targetColumn = columns.find(col => col.id === newColumnId);
        targetColumnName = targetColumn ? targetColumn.name : '';
      }

      if (rowChanged) {
        const rowId = newRowId as Id;
        await updateTaskRow(task.id, rowId);
        updatedTask.rowId = rowId;

        const targetRow = rows.find(row => row.id === newRowId);
        targetRowName = targetRow ? targetRow.name : '';
      }

      setTasks(prevTasks => prevTasks.map(t =>
        t.id === taskId ? updatedTask : t
      ));

      let message;
      if (columnChanged && rowChanged) {
        message = t('notifications.taskMovedToColumnAndRow', {
          title: task.title,
          column: targetColumnName,
          row: targetRowName
        });
      } else if (columnChanged) {
        message = t('notifications.taskMoved', {
          title: task.title,
          column: targetColumnName
        });
      } else if (rowChanged) {
        message = t('notifications.taskMovedToRow', {
          title: task.title,
          row: targetRowName
        });
      }

      toast.success(message);
    } catch (err) {
      console.error('Error moving task:', err);
      reportError(t, err);
      await refreshTasks();
    }
  };

  const handleTaskReorder = async (draggedTaskId: Id, targetTaskId: Id): Promise<void> => {
    if (blockIfReadOnly()) return;
    try {
      const draggedTask = tasks.find(t => t.id === draggedTaskId);
      const targetTask = tasks.find(t => t.id === targetTaskId);
      if (!draggedTask || !targetTask) return;

      if (draggedTask.columnId !== targetTask.columnId ||
          draggedTask.rowId !== targetTask.rowId) {
        return handleMoveTask(draggedTaskId, targetTask.columnId, targetTask.rowId);
      }

      const containerTasks = tasks.filter(
        t => t.columnId === targetTask.columnId && t.rowId === targetTask.rowId
      );

      const sortedTasks = [...containerTasks].sort((a, b) =>
        (a.position !== undefined && b.position !== undefined)
          ? a.position - b.position
          : 0
      );

      const draggedIndex = sortedTasks.findIndex(t => t.id === draggedTaskId);
      const targetIndex = sortedTasks.findIndex(t => t.id === targetTaskId);
      const newOrder = [...sortedTasks];
      newOrder.splice(draggedIndex, 1);
      newOrder.splice(targetIndex, 0, draggedTask);

      const updatedTasks = tasks.map(task => {
        const newIndex = newOrder.findIndex(t => t.id === task.id);
        if (newIndex !== -1) {
          return { ...task, position: newIndex };
        }
        return task;
      });

      setTasks(updatedTasks);

      try {
        await reorderTasks(newOrder.map(task => task.id));
      } catch (err) {
        if (err instanceof ConcurrentModificationError) {
          toast.info(t('notifications.changedBySomeoneElse'));
          await refreshTasks();
          return;
        }
        throw err;
      }

      await refreshTasks();

    } catch (err) {
      console.error('Error reordering tasks:', err);
      reportError(t, err);
      await refreshTasks();
    }
  };

  const handleUpdateTaskCompletion = async (taskId: Id, completed: boolean) => {
    if (blockIfReadOnly()) return false;
    try {
      const updated = await updateTaskCompletion(taskId, completed);
      setTasks(previous => previous.map(task =>
        task.id === taskId ? { ...task, completed: updated.completed } : task
      ));
      toast.success(completed
        ? t('notifications.taskCompleted')
        : t('notifications.taskReopened'));
      if (!completed) {
        await refreshTasks();
      }
      return true;
    } catch (err) {
      if (err instanceof ParentTaskNotCompletedError) {
        toast.error(t('notifications.parentTaskNotCompleted'));
      } else {
        console.error('Error updating task completion:', err);
        reportError(t, err);
      }
      return false;
    }
  };

  const handleSetDailyFocus = async (taskId: Id, dailyFocus: boolean) => {
    if (blockIfReadOnly()) return false;
    try {
      await setTaskDailyFocus(taskId, dailyFocus);
      setTasks(previous => previous.map(task =>
        task.id === taskId ? { ...task, dailyFocus } : task
      ));
      toast.success(dailyFocus
        ? t('notifications.dailyFocusAdded')
        : t('notifications.dailyFocusRemoved'));
      return true;
    } catch (err) {
      console.error('Error updating daily focus:', err);
      reportError(t, err);
      return false;
    }
  };

  const handleDeleteTask = async (taskId: Id) => {
    refuseIfReadOnly();
    try {
      await deleteTask(taskId);
      setTasks(previous => previous.filter(task => task.id !== taskId));
      toast.success(t('notifications.taskDeleted'));
    } catch (err) {
      reportError(t, err);
      throw err;
    }
  };

  const handleGetUserWipStatus = (userId: Id) => getUserWipStatus(userId);

  const handleUpdateUserWipLimit = async (userId: Id, wipLimit: number | string) => {
    try {
      const result = await updateUserWipLimit(userId, wipLimit);

      toast.success(t('notifications.userWipLimitUpdated', { limit: wipLimit }));
      return result;
    } catch (err) {
      console.error('Error updating user WIP limit:', err);
      reportError(t, err);
      throw err;
    }
  };

  return {
    updateTaskName: handleUpdateTaskName,
    addTask: handleAddTask,
    moveTask: handleMoveTask,
    reorderTask: handleTaskReorder,
    updateTaskCompletion: handleUpdateTaskCompletion,
    setDailyFocus: handleSetDailyFocus,
    deleteTask: handleDeleteTask,
    getUserWipStatus: handleGetUserWipStatus,
    updateUserWipLimit: handleUpdateUserWipLimit,
  };
}
