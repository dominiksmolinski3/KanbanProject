/* eslint-disable react-refresh/only-export-components */
import { createContext, useState, useEffect, useContext } from 'react';
import { 
  fetchColumns, 
  fetchTasks, 
  updateTaskColumn, 
  deleteTask, 
  addTask, 
  addColumn, 
  updateColumnWipLimit, 
  deleteColumn,
  fetchRows,
  addRow,
  updateRowWipLimit,
  deleteRow,
  updateTaskRow,
  updateColumnPosition,
  updateRowPosition,
  updateTaskPosition,
  updateTaskName,
  updateRowName,
  updateColumnName,
  getUserWipLimit,
  updateUserWipLimit,
} from '../services/api';

const KanbanContext = createContext();

export function KanbanProvider({ children }) {
  const [columns, setColumns] = useState([]);
  const [tasks, setTasks] = useState([]);
  const [rows, setRows] = useState([]);
  const [users, setUsers] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [draggedItem, setDraggedItem] = useState(null);
  
  // Map of column ids to their frontend keys
  const [columnMap, setColumnMap] = useState({});
  
  // Load initial data
  useEffect(() => {
    const loadData = async () => {
      try {
        setLoading(true);
        
        // Fetch columns
        const columnsData = await fetchColumns();
        
        // Sort columns by position field
        const sortedColumns = columnsData.sort((a, b) => a.position - b.position);
        
        // Create column mapping
        const newColumnMap = {};
        sortedColumns.forEach(column => {
          const columnKey = column.name.toLowerCase().replace(/\s+/g, '-');
          newColumnMap[columnKey] = column.id;
        });
        
        setColumns(sortedColumns);
        setColumnMap(newColumnMap);
        
        // Fetch rows FIRST
        let rowsData = [];
        try {
          rowsData = await fetchRows();
          // Sort rows by position field
          const sortedRows = rowsData.sort((a, b) => a.position - b.position);
          setRows(sortedRows);
        } catch (rowErr) {
          console.error('Error fetching rows:', rowErr);
          rowsData = [];
          setRows([]);
        }
        
        // THEN fetch tasks
        const tasksData = await fetchTasks();
        
        // If rows exist, ensure all tasks have valid rowIds
        if (rowsData.length > 0) {
          const defaultRowId = rowsData[0].id;
          const updatedTasks = tasksData.map(task => 
            (!task.rowId || task.rowId === null) ? { ...task, rowId: defaultRowId } : task
          );
          setTasks(updatedTasks);
        } else {
          // No rows, just set tasks as-is
          setTasks(tasksData);
        }
        
        setLoading(false);
      } catch (err) {
        setError(err.message);
        setLoading(false);
      }
    };
    
    loadData();
  }, []);
  
  // Update task name
  const handleUpdateTaskName = async (taskId, newName) => {
    try {
      await updateTaskName(taskId, newName);
      setTasks(tasks.map(task => 
        task.id === taskId ? { ...task, title: newName } : task
      ));
      return true;
    } catch (err) {
      console.error('Error updating task name:', err);
      setError(err.message);
      return false;
    }
  };

  // Update column name
  const handleUpdateColumnName = async (columnId, newName) => {
    try {
      await updateColumnName(columnId, newName);
      
      // Update columns in state
      setColumns(columns.map(column => 
        column.id === columnId ? { ...column, name: newName } : column
      ));
      
      // Also update columnMap if needed
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
      
      return true;
    } catch (err) {
      console.error('Error updating column name:', err);
      setError(err.message);
      return false;
    }
  };

  // Update row name
  const handleUpdateRowName = async (rowId, newName) => {
    try {
      await updateRowName(rowId, newName);
      setRows(rows.map(row => 
        row.id === rowId ? { ...row, name: newName } : row
      ));
      return true;
    } catch (err) {
      console.error('Error updating row name:', err);
      setError(err.message);
      return false;
    }
  };

  // Add a new task
  const handleAddTask = async (title, rowId) => {
    try {
      if (columns.length === 0) {
        throw new Error('Nie ma żadnej kolumny. Dodaj najpierw kolumnę.');
      }
      
      const firstColumn = columns[0];
      
      // First, create the task with columnId
      const newTask = await addTask(title, firstColumn.id);
      
      // Ensure rowId is set when in row+column view
      if (rows.length > 0) {
        // If rowId is provided, use it; otherwise, use the first row
        const targetRowId = rowId || rows[0].id;
        await updateTaskRow(newTask.id, targetRowId);
        
        // Update the local task state to reflect the change immediately
        const updatedTask = { ...newTask, rowId: targetRowId };
        setTasks(prevTasks => [...prevTasks, updatedTask]);
      } else {
        // No rows, just add the task to the state
        setTasks(prevTasks => [...prevTasks, newTask]);
      }
      
      // Refresh all tasks to ensure everything is in sync
      await refreshTasks();
      return newTask;
    } catch (err) {
      setError(err.message);
      throw err;
    }
  };

  const handleTaskReorder = async (draggedTaskId, targetTaskId) => {
    try {
      // Find both tasks
      const draggedTask = tasks.find(t => t.id === draggedTaskId);
      const targetTask = tasks.find(t => t.id === targetTaskId);
      
      if (!draggedTask || !targetTask) return;
      
      // Check if they're in the same column and row
      if (draggedTask.columnId !== targetTask.columnId || 
          draggedTask.rowId !== targetTask.rowId) {
        // If not in the same container, let the normal move task handle it
        return handleMoveTask(draggedTaskId, targetTask.columnId, targetTask.rowId);
      }
      
      // Get all tasks in this column and row
      const containerTasks = tasks.filter(
        t => t.columnId === targetTask.columnId && t.rowId === targetTask.rowId
      );
      
      // Sort tasks by position if available, otherwise use current order
      const sortedTasks = [...containerTasks].sort((a, b) => 
        (a.position !== undefined && b.position !== undefined) 
          ? a.position - b.position 
          : 0
      );
      
      // Find indices
      const draggedIndex = sortedTasks.findIndex(t => t.id === draggedTaskId);
      const targetIndex = sortedTasks.findIndex(t => t.id === targetTaskId);
      
      // Reorder
      const newOrder = [...sortedTasks];
      newOrder.splice(draggedIndex, 1);
      newOrder.splice(targetIndex, 0, draggedTask);
      
      // Update local state for immediate feedback
      const updatedTasks = tasks.map(task => {
        const newIndex = newOrder.findIndex(t => t.id === task.id);
        if (newIndex !== -1) {
          return { ...task, position: newIndex };
        }
        return task;
      });
      
      setTasks(updatedTasks);
      
      // Update positions in the backend
      const updatePromises = newOrder.map(async (task, index) => {
        try {
          await updateTaskPosition(task.id, index);
        } catch (error) {
          console.error(`Error updating task position for ${task.id}:`, error);
        }
      });
      
      await Promise.all(updatePromises);
      await refreshTasks();
      
    } catch (err) {
      console.error('Error reordering tasks:', err);
      setError(err.message);
      throw err;
    }
  };

  const refreshTasks = async () => {
    try {
      
      // Only fetch and update tasks
      const tasksData = await fetchTasks();
      setTasks(tasksData);
      
      setLoading(false);
    } catch (err) {
      console.error('Error refreshing tasks:', err);
      setError(err.message);
      setLoading(false);
    }
  };

  const refreshBoard = async () => {
    try {
      setLoading(true);
      
      // Fetch and update columns
      const columnsData = await fetchColumns();
      const sortedColumns = columnsData.sort((a, b) => a.position - b.position);
      setColumns(sortedColumns);
      
      // Fetch and update rows
      const rowsData = await fetchRows();
      const sortedRows = rowsData.sort((a, b) => a.position - b.position);
      setRows(sortedRows);

      refreshTasks();
      
      setLoading(false);
    } catch (err) {
      console.error('Error refreshing board data:', err);
      setError(err.message);
      setLoading(false);
    }
  };
  
  // Add a new column
  const handleAddColumn = async (name, wipLimit) => {
    try {
      const newColumn = await addColumn(name, wipLimit);
      
      // Update column map
      const columnKey = name.toLowerCase().replace(/\s+/g, '-');
      setColumnMap(prev => ({
        ...prev,
        [columnKey]: newColumn.id
      }));
      
      setColumns([...columns, newColumn]);
      return newColumn;
    } catch (err) {
      setError(err.message);
      throw err;
    }
  };
  
  // Add a new row
  const handleAddRow = async (name, wipLimit) => {
    try {
      const newRow = await addRow(name, wipLimit);
      setRows([...rows, newRow]);
      
      // If this is the first row being added, assign all tasks with null rowId to this row
      if (rows.length === 0) {
        const tasksToUpdate = tasks.filter(task => task.rowId === null || task.rowId === undefined);
        
        const updatedTasks = tasks.map(task => 
          (task.rowId === null || task.rowId === undefined)
            ? { ...task, rowId: newRow.id } 
            : task
        );
        
        setTasks(updatedTasks);
        
        // Update tasks in backend
        tasksToUpdate.forEach(async (task) => {
          await updateTaskRow(task.id, newRow.id);
        });
      }
      
      return newRow;
    } catch (err) {
      setError(err.message);
      throw err;
    }
  };
  
  // Update column WIP limit
  const handleUpdateWipLimit = async (columnId, newLimit) => {
    try {
      await updateColumnWipLimit(columnId, newLimit);
      
      // Fetch fresh column data while preserving order
      const updatedColumns = await fetchColumns();
      const columnOrderMap = {};
      columns.forEach((col, index) => {
        columnOrderMap[col.id] = index;
      });
      
      // Sort updated columns based on position
      const sortedUpdatedColumns = updatedColumns.sort((a, b) => a.position - b.position);
      
      setColumns(sortedUpdatedColumns);
    } catch (err) {
      console.error('Failed to update WIP limit:', err);
      setError('Failed to update WIP limit. Please try again.');
    }
  };

  // get user WIP limit
const handlegetUserWipLimit = async (userId) => {
  try {
    return await getUserWipLimit(userId);
  } catch (err) {
    console.error('Error checking user WIP limit:', err);
    setError(err.message);
    throw err;
  }
};

// Update user WIP limit
const handleUpdateUserWipLimit = async (userId, wipLimit) => {
  try {
    const result = await updateUserWipLimit(userId, wipLimit);
    
    // If we're storing user data in state, you might want to update it here
    // This would depend on how you're handling user data elsewhere
    setUsers(prevUsers => 
      prevUsers.map(user => 
        user.id === userId ? { ...user, wipLimit } : user
      )
    );
    
    return result;
  } catch (err) {
    console.error('Error updating user WIP limit:', err);
    setError(err.message);
    throw err;
  }
};
  
  // Update row WIP limit
  const handleUpdateRowWipLimit = async (rowId, newLimit) => {
    try {
      await updateRowWipLimit(rowId, newLimit);
      
      // Fetch fresh row data
      const updatedRows = await fetchRows();
      // Sort by position
      const sortedRows = updatedRows.sort((a, b) => a.position - b.position);
      setRows(sortedRows);
    } catch (err) {
      console.error('Failed to update row WIP limit:', err);
      setError('Failed to update row WIP limit. Please try again.');
    }
  };
  
  // Delete column
  const handleDeleteColumn = async (columnId) => {
    try {
      // Find an alternative column first
      const alternativeColumn = columns.find(col => col.id !== columnId);
      
      if (!alternativeColumn) {
        // If this is the last column, just delete it
        await deleteColumn(columnId);
        setColumns([]);
        return;
      }
      
      // Move tasks from deleted column to alternative column in the backend FIRST
      const tasksToMove = tasks.filter(task => task.columnId === columnId);
      
      // Update all tasks in backend before deleting the column
      for (const task of tasksToMove) {
        try {
          await updateTaskColumn(task.id, alternativeColumn.id);
        } catch (updateErr) {
          console.error(`Error updating task ${task.id} column:`, updateErr);
          // Continue with other tasks even if one fails
        }
      }
      
      // Now delete the column from backend
      await deleteColumn(columnId);
      
      // Update column map
      const updatedColumnMap = { ...columnMap };
      for (const [key, value] of Object.entries(updatedColumnMap)) {
        if (value === columnId) {
          delete updatedColumnMap[key];
          break;
        }
      }
      setColumnMap(updatedColumnMap);
      
      // Update local state
      setColumns(columns.filter(column => column.id !== columnId));
      
      // Update tasks in local state to match backend changes
      const updatedTasks = tasks.map(task => 
        task.columnId === columnId 
          ? { ...task, columnId: alternativeColumn.id } 
          : task
      );
      
      setTasks(updatedTasks);
      
      // Force a refresh to ensure everything is in sync
      await refreshTasks();
      
    } catch (err) {
      console.error('Error deleting column:', err);
      setError(err.message);
      throw err;
    }
  };
  
// Delete row
const handleDeleteRow = async (rowId) => {
  try {
    // Check if this is the last row
    const isLastRow = rows.length === 1;
    
    // Get all tasks that belong to this row
    const tasksToUpdate = tasks.filter(task => task.rowId === rowId);
    
    if (!isLastRow) {
      // Find an alternative row
      const remainingRows = rows.filter(row => row.id !== rowId);
      const targetRowId = remainingRows[0].id;
      
      // Update each task in the backend BEFORE deleting the row
      for (const task of tasksToUpdate) {
        await updateTaskRow(task.id, targetRowId);
      }
      
      // Now delete the row
      await deleteRow(rowId);
      
      // Update local state
      setRows(rows.filter(row => row.id !== rowId));
      
      // Update tasks in local state
      const updatedTasks = tasks.map(task => 
        task.rowId === rowId ? { ...task, rowId: targetRowId } : task
      );
      setTasks(updatedTasks);
    } 
    // Special handling for the last row
    else {
      // First, update all tasks to have null rowId in the database
      for (const task of tasksToUpdate) {
        // Use the existing updateTaskRow function with a special null handling
        try {
          // Call our updateTaskRow that handles null values
          await updateTaskRow(task.id, null);
        } catch (updateErr) {
          console.error('Error updating task row to null:', updateErr);
          // Continue with other tasks even if one fails
        }
      }
      
      // Now delete the row
      await deleteRow(rowId, true);
      
      // Clear rows in local state
      setRows([]);
      
      // Update tasks in local state
      const updatedTasks = tasks.map(task => 
        task.rowId === rowId ? { ...task, rowId: null } : task
      );
      setTasks(updatedTasks);
    }
    
    // Force refresh the board data
    await refreshBoard();
    
  } catch (err) {
    console.error('Error deleting row:', err);
    setError(err.message);
    throw err;
  }
};
  
  // Delete task
  const handleDeleteTask = async (taskId) => {
    try {
      await deleteTask(taskId);
      setTasks(tasks.filter(task => task.id !== taskId));
    } catch (err) {
      setError(err.message);
      throw err;
    }
  };
  
  // Move task between columns and/or rows
  const handleMoveTask = async (taskId, newColumnId, newRowId) => {
    try {
      const task = tasks.find(t => t.id === taskId);
      if (!task) return;
      
      // Only update if something actually changed
      const columnChanged = newColumnId !== undefined && newColumnId !== task.columnId;
      const rowChanged = newRowId !== undefined && newRowId !== task.rowId;
      
      if (!columnChanged && !rowChanged) return;
      
      let updatedTask = { ...task };
      
      // Update column if needed
      if (columnChanged) {
        await updateTaskColumn(taskId, newColumnId);
        updatedTask.columnId = newColumnId;
      }
      
      // Update row if needed
      if (rowChanged) {
        await updateTaskRow(taskId, newRowId);
        updatedTask.rowId = newRowId;
      }
      
      setTasks(tasks.map(t => t.id === taskId ? updatedTask : t));
    } catch (err) {
      setError(err.message);
      throw err;
    }
  };

  // Move column 
  const handleMoveColumn = async (columnId, targetColumnId) => {
    try {
      // Find the index of both columns
      const columnIndex = columns.findIndex(col => col.id === columnId);
      const targetIndex = columns.findIndex(col => col.id === targetColumnId);
      
      if (columnIndex === -1 || targetIndex === -1) return;
      
      // Create a new column array with the moved column
      const newColumns = [...columns];
      const [movedColumn] = newColumns.splice(columnIndex, 1);
      newColumns.splice(targetIndex, 0, movedColumn);
      
      // Update the local state first for immediate feedback
      setColumns(newColumns);
      
      // Update positions in the backend
      const updatePromises = newColumns.map(async (column, index) => {
        try {
          await updateColumnPosition(column.id, index);
        } catch (error) {
          console.error(`Error updating column position for ${column.id}:`, error);
        }
      });
      
      await Promise.all(updatePromises);
      
    } catch (err) {
      console.error('Error moving column:', err);
      setError(err.message);
      throw err;
    }
  };

  // Move row
  const handleMoveRow = async (rowId, targetRowId) => {
    try {
      // Find the index of both rows
      const rowIndex = rows.findIndex(row => row.id === rowId);
      const targetIndex = rows.findIndex(row => row.id === targetRowId);
      
      if (rowIndex === -1 || targetIndex === -1) return;
      
      // Create a new row array with the moved row
      const newRows = [...rows];
      const [movedRow] = newRows.splice(rowIndex, 1);
      newRows.splice(targetIndex, 0, movedRow);
      
      // Update the local state first for immediate feedback
      setRows(newRows);
      
      // Update positions in the backend
      const updatePromises = newRows.map(async (row, index) => {
        try {
          await updateRowPosition(row.id, index);
        } catch (error) {
          console.error(`Error updating row position for ${row.id}:`, error);
        }
      });
      
      await Promise.all(updatePromises);
      
    } catch (err) {
      console.error('Error moving row:', err);
      setError(err.message);
      throw err;
    }
  };

// Replace the handleDragStart method in KanbanContext.jsx
const handleDragStart = (e, id, type = 'task', sourceColumnId = null, sourceRowId = null) => {
  // Create a clear data structure
  let data;
  
  if (type === 'task') {
    data = {
      id,
      type,
      sourceColumnId,
      sourceRowId
    };
  } else {
    data = { id, type };
  }
  
  // Set the data in standard format
  const dataString = JSON.stringify(data);
  e.dataTransfer.setData(`application/${type}`, dataString);
  
  e.dataTransfer.setData('text/plain', dataString);
  
  e.dataTransfer.effectAllowed = 'move';
  
  // Store the dragged item info in state
  setDraggedItem({ id, type, sourceColumnId, sourceRowId });
  
  // For backwards compatibility, add the data with the old format too
  if (type === 'task') {
    e.dataTransfer.setData('taskId', id);
    e.dataTransfer.setData('columnId', sourceColumnId);
  }
};

// Replace the handleDrop method in KanbanContext.jsx
const handleDrop = (e, targetColumnId, targetRowId) => {
  e.preventDefault();
  
  console.log('Drop event:', { targetColumnId, targetRowId });
  
  // First, check if we're dropping a task
  if (e.dataTransfer.types.includes('application/task')) {
    try {
      const dataString = e.dataTransfer.getData('application/task');
      console.log('Task data string:', dataString);
      
      const taskData = JSON.parse(dataString);
      console.log('Parsed task data:', taskData);
      
      // Extract the task ID
      const taskId = taskData.id;
      
      // Extract source information
      const sourceColumnId = taskData.sourceColumnId;
      const sourceRowId = taskData.sourceRowId;
      
      console.log('Moving task:', { taskId, sourceColumnId, targetColumnId, sourceRowId, targetRowId });
      
      // Skip if source and target are identical
      if (sourceColumnId === targetColumnId && sourceRowId === targetRowId) {
        return;
      }
      
      // Move the task
      handleMoveTask(taskId, targetColumnId, targetRowId);
    } catch (err) {
      console.error('Error processing task drop:', err);
      
      // Fallback: try to get the task ID directly
      try {
        const taskId = e.dataTransfer.getData('taskId');
        const sourceColumnId = e.dataTransfer.getData('columnId');
        
        if (taskId && sourceColumnId !== targetColumnId) {
          console.log('Fallback: Moving task with direct IDs', { taskId, targetColumnId });
          handleMoveTask(taskId, targetColumnId, targetRowId);
        }
      } catch (fallbackErr) {
        console.error('Fallback error:', fallbackErr);
      }
    }
  }
  
  // Check for column data
  else if (e.dataTransfer.types.includes('application/column')) {
    try {
      const dataString = e.dataTransfer.getData('application/column');
      console.log('Column data string:', dataString);
      
      const columnData = JSON.parse(dataString);
      const columnId = columnData.id;
      
      // Don't drop on itself
      if (columnId !== targetColumnId) {
        handleMoveColumn(columnId, targetColumnId);
      }
    } catch (err) {
      console.error('Error processing column drop:', err);
    }
  }
  
  // Check for row data
  else if (e.dataTransfer.types.includes('application/row')) {
    try {
      const dataString = e.dataTransfer.getData('application/row');
      console.log('Row data string:', dataString);
      
      const rowData = JSON.parse(dataString);
      const rowId = rowData.id;
      
      // Don't drop on itself
      if (rowId !== targetRowId) {
        handleMoveRow(rowId, targetRowId);
      }
    } catch (err) {
      console.error('Error processing row drop:', err);
    }
  }
  
  // Clear the dragged item state
  setDraggedItem(null);
};
  
  const handleDragOver = (e) => {
    if (e.preventDefault) {
      e.preventDefault(); // Necessary to allow dropping
    }
    
    e.dataTransfer.dropEffect = 'move';
    return false;
  };
  
  const handleDragEnd = () => {
    setDraggedItem(null);
  };
  
  // Combine all drag and drop handlers
  const dragAndDrop = {
    draggedItem,
    handleDragStart,
    handleDragOver,
    handleDrop,
    handleDragEnd,
    handleTaskReorder
  };
  
  const value = {
    columns,
    tasks,
    rows,
    users,
    loading,
    error,
    columnMap,
    addTask: handleAddTask,
    addColumn: handleAddColumn,
    addRow: handleAddRow,
    updateWipLimit: handleUpdateWipLimit,
    updateRowWipLimit: handleUpdateRowWipLimit,
    deleteColumn: handleDeleteColumn,
    deleteRow: handleDeleteRow,
    deleteTask: handleDeleteTask,
    moveTask: handleMoveTask,
    moveColumn: handleMoveColumn,
    moveRow: handleMoveRow,
    refreshTasks,
    refreshBoard,
    updateTaskName: handleUpdateTaskName,
    updateColumnName: handleUpdateColumnName,
    updateRowName: handleUpdateRowName,
    getUserWipLimit: handlegetUserWipLimit,
    updateUserWipLimit: handleUpdateUserWipLimit,
    dragAndDrop // Export drag and drop handlers
  };
  
  return <KanbanContext.Provider value={value}>{children}</KanbanContext.Provider>;
}

export const useKanban = () => {
  const context = useContext(KanbanContext);
  if (!context) {
    throw new Error('useKanban must be used within a KanbanProvider');
  }
  return context;
};

export default KanbanContext;