import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import type { BoardModel } from '../../board/boardModel';
import type { ColumnSummary, Id, RowSummary, Task } from '../../types/api';
import Icon from '../Icon';
import BoardCell from './BoardCell';
import ColumnHeader from './ColumnHeader';
import RowHeader from './RowHeader';
import { useDeleteConfirmation } from './useDeleteConfirmation';

export type AddRequest =
  | { type: 'task'; columnId: Id; rowId: Id }
  | { type: 'column' }
  | { type: 'row' };

interface BoardGridProps {
  model: BoardModel<ColumnSummary, RowSummary, Task>;
  phoneColumnId: Id | undefined;
  isCollapsed: (rowId: Id) => boolean;
  toggleLane: (rowId: Id) => void;
  onAdd: (request: AddRequest) => void;
}

export default function BoardGrid({ model, phoneColumnId, isCollapsed, toggleLane, onAdd }: BoardGridProps) {
  const { t } = useTranslation();
  const { readOnly } = useKanban();
  const { deleteRowClick, deleteColumnClick } = useDeleteConfirmation();

  return (
    <div className="board-scroller">
      <table className="kanban-table">
        <thead>
          <tr>
            <th className="grid-corner" aria-hidden="true"></th>

            {model.columns.map(column => (
              <ColumnHeader key={column.id} column={column} onDelete={deleteColumnClick} />
            ))}
            {!readOnly && (
              <th className="grid-column-header add-placeholder-header">
                <button
                  className="add-column-btn"
                  title={t('column.add')}
                  onClick={() => onAdd({ type: 'column' })}
                >
                  <Icon name="plus" size="sm" /> {t('column.add')}
                </button>
              </th>
            )}
          </tr>
        </thead>
        <tbody>
          {model.rows.map(row => (
            <tr key={row.id} className={isCollapsed(row.id) ? 'lane-row collapsed' : 'lane-row'}>
              <RowHeader row={row} collapsed={isCollapsed(row.id)} onToggle={toggleLane} onDelete={deleteRowClick} />

              {model.columns.map(column => (
                <BoardCell
                  key={`${row.id}-${column.id}`}
                  column={column}
                  row={row}
                  tasks={model.tasksIn(column.id, row.id)}
                  collapsed={isCollapsed(row.id)}
                  phoneActive={column.id === phoneColumnId}
                  onAddTask={(columnId, rowId) => onAdd({ type: 'task', columnId, rowId })}
                />
              ))}
              <td className="grid-cell grid-filler" />
            </tr>
          ))}
          {!readOnly && (
            <tr>
              <td className="grid-row-header add-placeholder-row">
                <button
                  className="add-row-btn"
                  title={t('row.add')}
                  onClick={() => onAdd({ type: 'row' })}
                >
                  <Icon name="plus" size="sm" /> {t('row.add')}
                </button>
              </td>
              {model.columns.map((column) => (
                <td key={`add-row-empty-${column.id}`} className="grid-cell grid-filler"/>
              ))}
              <td className="grid-cell grid-filler" />
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
