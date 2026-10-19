import { toast } from 'react-toastify';
import {
  addColumn,
  addRow,
  ConcurrentModificationError,
  deleteColumn,
  deleteRow,
  fetchColumns,
  fetchRows,
  reorderColumns,
  reorderRows,
  updateColumnName,
  updateColumnWipLimit,
  updateRowName,
  updateRowWipLimit,
  updateTaskColumn,
  updateTaskRow,
} from '../../services/api';
import type { Id } from '../../types/api';
import { reportError } from './reportError';
import type { LooseId, SharedActionDeps } from './types';

export function useLayoutActions({
  t, blockIfReadOnly, refuseIfReadOnly,
  columns, setColumns, rows, setRows, tasks, setTasks,
  refreshTasks, refreshBoard,
}: SharedActionDeps) {
  const handleUpdateColumnName = async (columnId: Id, newName: string) => {
    if (blockIfReadOnly()) return false;
    try {
      await updateColumnName(columnId, newName);
      setColumns(columns.map(column =>
        column.id === columnId ? { ...column, name: newName } : column
      ));

      toast.success(t('notifications.columnUpdated'));
      return true;
    } catch (err) {
      console.error('Error updating column name:', err);
      reportError(t, err);
      return false;
    }
  };

  const handleUpdateRowName = async (rowId: Id, newName: string) => {
    if (blockIfReadOnly()) return false;
    try {
      await updateRowName(rowId, newName);
      setRows(rows.map(row =>
        row.id === rowId ? { ...row, name: newName } : row
      ));
      toast.success(t('notifications.rowUpdated'));
      return true;
    } catch (err) {
      console.error('Error updating row name:', err);
      reportError(t, err);
      return false;
    }
  };

  const handleAddColumn = async (name: string, wipLimit: number | string) => {
    refuseIfReadOnly();
    try {
      const newColumn = await addColumn(name, wipLimit);
      setColumns(prev => [...prev, newColumn]);
      toast.success(t('notifications.columnAdded', { name }));
      return newColumn;
    } catch (err) {
      reportError(t, err);
      throw err;
    }
  };

  const handleAddRow = async (name: string, wipLimit: number | string) => {
    refuseIfReadOnly();
    try {
      const newRow = await addRow(name, wipLimit);
      setRows([...rows, newRow]);
      if (rows.length === 0) {
        const tasksToUpdate = tasks.filter(task => task.rowId === null || task.rowId === undefined);

        const updatedTasks = tasks.map(task =>
          (task.rowId === null || task.rowId === undefined)
            ? { ...task, rowId: newRow.id }
            : task
        );

        setTasks(updatedTasks);
        tasksToUpdate.forEach(async (task) => {
          await updateTaskRow(task.id, newRow.id);
        });
      }

      toast.success(t('notifications.rowAdded', { name }));
      return newRow;
    } catch (err) {
      reportError(t, err);
      throw err;
    }
  };

  const handleUpdateWipLimit = async (columnId: Id, newLimit: number | string) => {
    if (blockIfReadOnly()) return false;
    try {
      const columnToUpdate = columns.find(col => String(col.id) === String(columnId));
      const columnName = columnToUpdate ? columnToUpdate.name : t('taskDetails.unknownColumn');

      await updateColumnWipLimit(columnId, newLimit);
      const updatedColumns = await fetchColumns();
      const sortedUpdatedColumns = updatedColumns.sort((a, b) => a.position - b.position);

      setColumns(sortedUpdatedColumns);
      toast.success(t('notifications.wipLimitUpdated', { name: columnName, limit: newLimit }));
      return true;
    } catch (err) {
      console.error('Failed to update WIP limit:', err);
      reportError(t, err);
      return false;
    }
  };

  const handleUpdateRowWipLimit = async (rowId: Id, newLimit: number | string) => {
    if (blockIfReadOnly()) return false;
    try {
      const rowToUpdate = rows.find(r => String(r.id) === String(rowId));
      const rowName = rowToUpdate ? rowToUpdate.name : t('taskDetails.unknownRow');

      await updateRowWipLimit(rowId, newLimit);
      const updatedRows = await fetchRows();
      const sortedRows = updatedRows.sort((a, b) => a.position - b.position);

      setRows(sortedRows);
      toast.success(t('notifications.rowWipLimitUpdated', { name: rowName, limit: newLimit }));
      return true;
    } catch (err) {
      console.error('Failed to update row WIP limit:', err);
      reportError(t, err);
      return false;
    }
  };

  const handleDeleteColumn = async (columnId: Id) => {
    refuseIfReadOnly();
    try {
      const columnToDelete = columns.find(col => col.id === columnId);
      const columnName = columnToDelete ? columnToDelete.name : t('taskDetails.unknownColumn');

      const alternativeColumn = columns.find(col => col.id !== columnId);

      if (!alternativeColumn) {
        await deleteColumn(columnId);
        setColumns([]);
        toast.info(t('notifications.lastColumnDeleted', { name: columnName }));
        return;
      }

      const tasksToMove = tasks.filter(task => task.columnId === columnId);
      for (const task of tasksToMove) {
        await updateTaskColumn(task.id, alternativeColumn.id);
      }

      await deleteColumn(columnId);
      setColumns(columns.filter(column => column.id !== columnId));

      const updatedTasks = tasks.map(task =>
        task.columnId === columnId
          ? { ...task, columnId: alternativeColumn.id }
          : task
      );

      setTasks(updatedTasks);
      await refreshTasks();
      toast.success(t('notifications.columnDeleted', { name: columnName }));

    } catch (err) {
      console.error('Error deleting column:', err);
      reportError(t, err);
      await refreshBoard();
      throw err;
    }
  };

  const handleDeleteRow = async (rowId: Id) => {
    refuseIfReadOnly();
    try {
      const rowToDelete = rows.find(row => row.id === rowId);
      const rowName = rowToDelete ? rowToDelete.name : t('taskDetails.unknownRow');

      const isLastRow = rows.length === 1;
      const tasksToUpdate = tasks.filter(task => task.rowId === rowId);
      let moveFailed = false;

      if (!isLastRow) {
        const remainingRows = rows.filter(row => row.id !== rowId);
        const targetRowId = remainingRows[0].id;

        for (const task of tasksToUpdate) {
          try {
            await updateTaskRow(task.id, targetRowId);
          } catch (moveErr) {
            moveFailed = true;
            console.error('Could not move a task out of the row being deleted:', moveErr);
          }
        }

        await deleteRow(rowId);
        setRows(rows.filter(row => row.id !== rowId));
        const updatedTasks = tasks.map(task =>
          task.rowId === rowId ? { ...task, rowId: targetRowId } : task
        );
        setTasks(updatedTasks);
      }
      else {
        for (const task of tasksToUpdate) {
          try {
            await updateTaskRow(task.id, null);
          } catch (updateErr) {
            moveFailed = true;
            console.error('Error updating task row to null:', updateErr);
          }
        }

        await deleteRow(rowId);
        setRows([]);
        const updatedTasks = tasks.map(task =>
          task.rowId === rowId ? { ...task, rowId: null } : task
        );
        setTasks(updatedTasks);
      }
      await refreshBoard();

      if (moveFailed) {
        toast.warning(t('notifications.rowDeletedCardsNotMoved', { name: rowName }));
      } else if (isLastRow) {
        toast.info(t('notifications.lastRowDeleted', { name: rowName }));
      } else {
        toast.success(t('notifications.rowDeleted', { name: rowName }));
      }
    } catch (err) {
      console.error('Error deleting row:', err);
      reportError(t, err);
      await refreshBoard();
      throw err;
    }
  };

  const handleMoveColumn = async (columnId: LooseId, targetColumnId: LooseId | null | undefined) => {
    if (blockIfReadOnly()) return;
    try {
      const columnIndex = columns.findIndex(col => col.id === columnId);
      const targetIndex = columns.findIndex(col => col.id === targetColumnId);

      if (columnIndex === -1 || targetIndex === -1) return;

      const movedColumn = columns[columnIndex];
      const newColumns = [...columns];
      newColumns.splice(columnIndex, 1);
      newColumns.splice(targetIndex, 0, movedColumn);
      setColumns(newColumns);

      try {
        const reordered = await reorderColumns(newColumns.map(column => column.id));
        setColumns(reordered);
      } catch (err) {
        if (err instanceof ConcurrentModificationError) {
          toast.info(t('notifications.changedBySomeoneElse'));
          await refreshBoard();
          return;
        }
        throw err;
      }

      toast.success(t('notifications.columnMoved', { name: movedColumn.name }));
    } catch (err) {
      console.error('Error moving column:', err);
      reportError(t, err);
      await refreshBoard();
    }
  };

  const handleMoveRow = async (rowId: LooseId, targetRowId: LooseId | null | undefined) => {
    if (blockIfReadOnly()) return;
    try {
      const rowIndex = rows.findIndex(row => row.id === rowId);
      const targetIndex = rows.findIndex(row => row.id === targetRowId);

      if (rowIndex === -1 || targetIndex === -1 || rowId === targetRowId) return;

      const movedRow = rows[rowIndex];
      const newRows = [...rows];
      newRows.splice(rowIndex, 1);
      newRows.splice(targetIndex, 0, movedRow);

      setRows(newRows);

      try {
        const reordered = await reorderRows(newRows.map(row => row.id));
        setRows(reordered);
      } catch (err) {
        if (err instanceof ConcurrentModificationError) {
          toast.info(t('notifications.changedBySomeoneElse'));
          await refreshBoard();
          return;
        }
        throw err;
      }

      toast.success(t('notifications.rowMoved', { name: movedRow.name }));
    } catch (err) {
      console.error('Error moving row:', err);
      reportError(t, err);
      await refreshBoard();
    }
  };

  return {
    updateColumnName: handleUpdateColumnName,
    updateRowName: handleUpdateRowName,
    addColumn: handleAddColumn,
    addRow: handleAddRow,
    updateWipLimit: handleUpdateWipLimit,
    updateRowWipLimit: handleUpdateRowWipLimit,
    deleteColumn: handleDeleteColumn,
    deleteRow: handleDeleteRow,
    moveColumn: handleMoveColumn,
    moveRow: handleMoveRow,
  };
}
