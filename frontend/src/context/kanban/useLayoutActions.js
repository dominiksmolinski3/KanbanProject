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

export function useLayoutActions({
  t, setError, blockIfReadOnly, refuseIfReadOnly,
  columns, setColumns, rows, setRows, tasks, setTasks, columnMap, setColumnMap,
  refreshTasks, refreshBoard,
}) {
  const handleUpdateColumnName = async (columnId, newName) => {
    if (blockIfReadOnly()) return false;
    try {
      await updateColumnName(columnId, newName);
      setColumns(columns.map(column =>
        column.id === columnId ? { ...column, name: newName } : column
      ));

      const updatedColumn = columns.find(column => column.id === columnId);
      if (updatedColumn) {
        const oldKey = updatedColumn.name.toLowerCase().replace(/\s+/g, '-');
        const newKey = newName.toLowerCase().replace(/\s+/g, '-');

        setColumnMap(prevMap => {
          const newMap = { ...prevMap };
          if (newMap[oldKey]) {
            delete newMap[oldKey];
            newMap[newKey] = columnId;
          }
          return newMap;
        });
      }

      toast.success(t('notifications.columnUpdated'));
      return true;
    } catch (err) {
      console.error('Error updating column name:', err);
      setError(err.message);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      return false;
    }
  };

  const handleUpdateRowName = async (rowId, newName) => {
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
      setError(err.message);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      return false;
    }
  };

  const handleAddColumn = async (name, wipLimit) => {
    refuseIfReadOnly();
    try {
      const newColumn = await addColumn(name, wipLimit);
      const columnKey = name.toLowerCase().replace(/\s+/g, '-');
      setColumnMap(prev => ({
        ...prev,
        [columnKey]: newColumn.id
      }));

      setColumns(prev => [...prev, newColumn]);
      toast.success(t('notifications.columnAdded', { name }));
      return newColumn;
    } catch (err) {
      setError(err.message);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      throw err;
    }
  };

  const handleAddRow = async (name, wipLimit) => {
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
      setError(err.message);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      throw err;
    }
  };

  const handleUpdateWipLimit = async (columnId, newLimit) => {
    if (blockIfReadOnly()) return;
    try {
      const columnToUpdate = columns.find(col => String(col.id) === String(columnId));
      const columnName = columnToUpdate ? columnToUpdate.name : 'kolumny';

      await updateColumnWipLimit(columnId, newLimit);
      const updatedColumns = await fetchColumns();
      const sortedUpdatedColumns = updatedColumns.sort((a, b) => a.position - b.position);

      setColumns(sortedUpdatedColumns);
      toast.success(t('notifications.wipLimitUpdated', { name: columnName, limit: newLimit }));
    } catch (err) {
      console.error('Failed to update WIP limit:', err);
      setError('Failed to update WIP limit. Please try again.');
      toast.error(t('notifications.errorOccurred', { message: err.message }));
    }
  };

  const handleUpdateRowWipLimit = async (rowId, newLimit) => {
    if (blockIfReadOnly()) return;
    try {
      const rowToUpdate = rows.find(r => String(r.id) === String(rowId));
      const rowName = rowToUpdate ? rowToUpdate.name : 'wiersza';

      await updateRowWipLimit(rowId, newLimit);
      const updatedRows = await fetchRows();
      const sortedRows = updatedRows.sort((a, b) => a.position - b.position);

      setRows(sortedRows);
      toast.success(t('notifications.rowWipLimitUpdated', { name: rowName, limit: newLimit }));
    } catch (err) {
      console.error('Failed to update row WIP limit:', err);
      setError('Failed to update row WIP limit. Please try again.');
      toast.error(t('notifications.errorOccurred', { message: err.message }));
    }
  };

  const handleDeleteColumn = async (columnId) => {
    refuseIfReadOnly();
    try {
      const columnToDelete = columns.find(col => col.id === columnId);
      const columnName = columnToDelete ? columnToDelete.name : 'kolumna';

      const alternativeColumn = columns.find(col => col.id !== columnId);

      if (!alternativeColumn) {
        await deleteColumn(columnId);
        setColumns([]);
        toast.info(t('notifications.lastColumnDeleted', { name: columnName }));
        return;
      }

      const tasksToMove = tasks.filter(task => task.columnId === columnId);
      for (const task of tasksToMove) {
        try {
          await updateTaskColumn(task.id, alternativeColumn.id);
        } catch (updateErr) {
          console.error(`Error updating task ${task.id} column:`, updateErr);
        }
      }

      await deleteColumn(columnId);
      const updatedColumnMap = { ...columnMap };
      for (const [key, value] of Object.entries(updatedColumnMap)) {
        if (value === columnId) {
          delete updatedColumnMap[key];
          break;
        }
      }
      setColumnMap(updatedColumnMap);
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
      setError(err.message);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      throw err;
    }
  };

  const handleDeleteRow = async (rowId) => {
    refuseIfReadOnly();
    try {
      const rowToDelete = rows.find(row => row.id === rowId);
      const rowName = rowToDelete ? rowToDelete.name : 'wiersz';

      const isLastRow = rows.length === 1;
      const tasksToUpdate = tasks.filter(task => task.rowId === rowId);

      if (!isLastRow) {
        const remainingRows = rows.filter(row => row.id !== rowId);
        const targetRowId = remainingRows[0].id;

        for (const task of tasksToUpdate) {
          try {
            await updateTaskRow(task.id, targetRowId);
          } catch (moveErr) {
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

      if (isLastRow) {
        toast.info(t('notifications.lastRowDeleted', { name: rowName }));
      } else {
        toast.success(t('notifications.rowDeleted', { name: rowName }));
      }
    } catch (err) {
      console.error('Error deleting row:', err);
      setError(err.message);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      throw err;
    }
  };

  const handleMoveColumn = async (columnId, targetColumnId) => {
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
      setError(err.message);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      throw err;
    }
  };

  const handleMoveRow = async (rowId, targetRowId) => {
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
      setError(err.message);
      toast.error(t('notifications.errorOccurred', { message: err.message }));
      throw err;
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
