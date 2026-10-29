import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import type { WipDecoration } from '../../board/boardModel';
import type { ColumnSummary } from '../../types/api';
import EditableText from '../EditableText';
import Icon from '../Icon';
import WipMeter from '../WipMeter';
import { markDropTarget, unmarkDropTarget } from './dropTarget';

interface ColumnHeaderProps {
  column: ColumnSummary & WipDecoration;
  onDelete: (columnId: number) => void;
}

export default function ColumnHeader({ column, onDelete }: ColumnHeaderProps) {
  const { t } = useTranslation();
  const { dragAndDrop, readOnly, keyboardMove, updateColumnName } = useKanban();
  const isKeyboardTarget = Boolean(keyboardMove.held) && String(keyboardMove.held?.cell.columnId) === String(column.id);

  return (
    <th
      className={`grid-column-header wip-${column.wipState}${column.isOverLimit ? ' wip-exceeded' : ''}${isKeyboardTarget ? ' keyboard-target-name' : ''}`}
      draggable={!readOnly}
      onDragStart={(e) => dragAndDrop.handleDragStart(e, column.id, 'column')}
      onDragOver={(e) => dragAndDrop.handleDragOver(e)}
      onDragEnter={markDropTarget}
      onDragLeave={unmarkDropTarget}
      onDrop={(e) => dragAndDrop.handleDrop(e, column.id)}
      data-column-id={column.id}
      scope="col"
    >
      <div className="column-header-inner">
        <div className="column-title">
          {!readOnly && <span className="column-drag-handle" aria-hidden="true"><Icon name="grip" size="sm" /></span>}
          <EditableText
            id={column.id}
            text={column.name}
            onUpdate={updateColumnName}
            label={t('column.name')}
            renameLabel={t('column.rename', { name: column.name })}
            className="column-name"
            inputClassName="column-name-input"
            type="column"
            disabled={readOnly}
          />
          {!readOnly && (
            <button
              className="delete-column-btn icon-btn"
              title={t('column.delete')}
              aria-label={t('column.delete')}
              onClick={() => onDelete(column.id)}
            >
              <Icon name="trash" size="sm" />
            </button>
          )}
        </div>
        <WipMeter count={column.taskCount} limit={column.wipLimit} state={column.wipState} />
      </div>
    </th>
  );
}
