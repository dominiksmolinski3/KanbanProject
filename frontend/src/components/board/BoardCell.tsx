import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import type { WipDecoration } from '../../board/boardModel';
import type { ColumnSummary, RowSummary, Task as TaskModel } from '../../types/api';
import Task from '../Task';
import { DROP_TARGET, markDropTarget, unmarkDropTarget } from './dropTarget';

interface BoardCellProps {
  column: ColumnSummary & WipDecoration;
  row: RowSummary & WipDecoration;
  tasks: TaskModel[];
  collapsed: boolean;
  phoneActive: boolean;
  onAddTask: (columnId: number, rowId: number) => void;
}

export default function BoardCell({ column, row, tasks, collapsed, phoneActive, onAddTask }: BoardCellProps) {
  const { t } = useTranslation();
  const { dragAndDrop, keyboardMove, readOnly } = useKanban();
  const held = keyboardMove.held;
  const shouldHighlight = tasks.length > 0 && (column.isOverLimit || row.isOverLimit);
  const isKeyboardTarget = keyboardMove.isTarget(column.id, row.id);
  const showSlot = isKeyboardTarget && Boolean(held?.from)
    && !(String(held?.from.columnId) === String(column.id) && String(held?.from.rowId) === String(row.id));

  const onDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    dragAndDrop.handleDragOver(e);
  };

  const onDrop = (e: React.DragEvent<HTMLTableCellElement>) => {
    e.preventDefault();
    e.currentTarget.classList.remove(DROP_TARGET);
    dragAndDrop.handleDrop(e, column.id, row.id);
  };

  return (
    <td
      className={`grid-cell${shouldHighlight ? ' wip-exceeded-cell' : ''}${isKeyboardTarget ? ' keyboard-move-target' : ''}${collapsed ? ' lane-collapsed-cell' : ''}${phoneActive ? ' phone-active' : ''}`}
      data-column-id={column.id}
      data-row-id={row.id}
      data-keyboard-target={isKeyboardTarget ? 'true' : undefined}
      onDragOver={onDragOver}
      onDragEnter={markDropTarget}
      onDragLeave={unmarkDropTarget}
      onDrop={onDrop}
    >
      {collapsed ? (
        tasks.length > 0 && (
          <span className="lane-hidden-count">{t('board.lane.hidden', { n: tasks.length })}</span>
        )
      ) : (
        <div className="cell-stack">
          {tasks.map(task => (
            <Task
              key={task.id}
              task={task}
              columnId={column.id}
              rowId={row.id}
            />
          ))}
          {showSlot && <div className="keyboard-drop-slot">{t('board.keyboardMove.slot')}</div>}
          {!readOnly && (
            <button
              className="add-task-placeholder"
              onClick={() => onAddTask(column.id, row.id)}
              title={t('taskActions.addTaskHere')}
            >
              {t('taskActions.addTaskHere')}
            </button>
          )}
        </div>
      )}
    </td>
  );
}
