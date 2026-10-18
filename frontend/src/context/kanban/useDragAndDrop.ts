import { useState, type DragEvent } from 'react';
import type { LooseId, MoveTask } from './types';

type DragKind = 'task' | 'column' | 'row';

interface DraggedItem {
  id: LooseId;
  type: DragKind;
  sourceColumnId: LooseId | null;
  sourceRowId: LooseId | null;
}

interface DragAndDropDeps {
  moveTask: MoveTask;
  moveColumn: (columnId: LooseId, targetColumnId: LooseId | null | undefined) => Promise<void>;
  moveRow: (rowId: LooseId, targetRowId: LooseId | null | undefined) => Promise<void>;
  reorderTask: (draggedTaskId: number, targetTaskId: number) => Promise<void>;
}

export function useDragAndDrop({ moveTask, moveColumn, moveRow, reorderTask }: DragAndDropDeps) {
  const [draggedItem, setDraggedItem] = useState<DraggedItem | null>(null);

  const handleDragStart = (
    e: DragEvent<Element>,
    id: LooseId,
    type: DragKind = 'task',
    sourceColumnId: LooseId | null = null,
    sourceRowId: LooseId | null = null
  ) => {
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
      e.dataTransfer.setData('taskId', String(id));
      e.dataTransfer.setData('columnId', String(sourceColumnId));
    }
  };

  const handleDrop = (e: DragEvent<Element>, targetColumnId?: LooseId | null, targetRowId?: LooseId | null) => {
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

  const handleDragOver = (e: DragEvent<Element>) => {
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
