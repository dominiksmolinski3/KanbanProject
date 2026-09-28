import { useState } from 'react';

export function useDragAndDrop({ moveTask, moveColumn, moveRow, reorderTask }) {
  const [draggedItem, setDraggedItem] = useState(null);

  const handleDragStart = (e, id, type = 'task', sourceColumnId = null, sourceRowId = null) => {
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

    const dataString = JSON.stringify(data);
    e.dataTransfer.setData(`application/${type}`, dataString);
    e.dataTransfer.setData('text/plain', dataString);
    e.dataTransfer.effectAllowed = 'move';
    setDraggedItem({ id, type, sourceColumnId, sourceRowId });
    if (type === 'task') {
      e.dataTransfer.setData('taskId', id);
      e.dataTransfer.setData('columnId', sourceColumnId);
    }
  };

  const handleDrop = (e, targetColumnId, targetRowId) => {
    e.preventDefault();

    if (e.dataTransfer.types.includes('application/task')) {
      try {
        const dataString = e.dataTransfer.getData('application/task');
        const taskData = JSON.parse(dataString);
        const taskId = taskData.id;
        const sourceColumnId = taskData.sourceColumnId;
        const sourceRowId = taskData.sourceRowId;
        if (sourceColumnId === targetColumnId && sourceRowId === targetRowId) {
          return;
        }

        if (targetRowId && (!targetColumnId || targetColumnId === "null")) {
          moveTask(taskId, sourceColumnId, targetRowId);
        }
        else if (targetColumnId && (!targetRowId || targetRowId === "null")) {
          moveTask(taskId, targetColumnId, sourceRowId);
        }
        else {
          moveTask(taskId, targetColumnId, targetRowId);
        }
      } catch (err) {
        try {
          const taskId = e.dataTransfer.getData('taskId');
          const sourceColumnId = e.dataTransfer.getData('columnId');

          if (taskId && sourceColumnId !== targetColumnId) {
            moveTask(taskId, targetColumnId, targetRowId);
          }
        } catch (fallbackErr) {
          console.error('Fallback error:', fallbackErr, err);
        }
      }
    }
    else if (e.dataTransfer.types.includes('application/column')) {
      try {
        const columnData = JSON.parse(e.dataTransfer.getData('application/column'));
        const columnId = columnData.id;

        if (columnId !== targetColumnId) {
          moveColumn(columnId, targetColumnId);
        }
      } catch (err) {
        console.error('Error processing column drop:', err);
      }
    }
    else if (e.dataTransfer.types.includes('application/row')) {
      try {
        const rowData = JSON.parse(e.dataTransfer.getData('application/row'));
        const rowId = rowData.id;

        if (rowId !== targetRowId) {
          moveRow(rowId, targetRowId);
        }
      } catch (err) {
        console.error('Error processing row drop:', err);
      }
    }

    setDraggedItem(null);
  };

  const handleDragOver = (e) => {
    if (e.preventDefault) {
      e.preventDefault();
    }

    e.dataTransfer.dropEffect = 'move';
    return false;
  };

  const handleDragEnd = () => {
    setDraggedItem(null);
  };

  return {
    draggedItem,
    handleDragStart,
    handleDragOver,
    handleDrop,
    handleDragEnd,
    handleTaskReorder: reorderTask,
  };
}
