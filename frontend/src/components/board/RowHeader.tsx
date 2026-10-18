import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import type { WipDecoration } from '../../board/boardModel';
import type { RowSummary } from '../../types/api';
import EditableText from '../EditableText';
import Icon from '../Icon';
import WipMeter from '../WipMeter';
import { markDropTarget, unmarkDropTarget } from './dropTarget';

interface RowHeaderProps {
  row: RowSummary & WipDecoration;
  collapsed: boolean;
  onToggle: (rowId: number) => void;
  onDelete: (rowId: number) => void;
}

export default function RowHeader({ row, collapsed, onToggle, onDelete }: RowHeaderProps) {
  const { t } = useTranslation();
  const { dragAndDrop, readOnly, keyboardMove, updateRowName } = useKanban();
  const isKeyboardTarget = Boolean(keyboardMove.held) && String(keyboardMove.held?.cell.rowId) === String(row.id);
  const laneLabel = t(collapsed ? 'board.lane.expand' : 'board.lane.collapse', { name: row.name });

  return (
    <td
      className={`grid-row-header wip-${row.wipState}${row.isOverLimit ? ' wip-exceeded' : ''}${collapsed ? ' lane-collapsed' : ''}${isKeyboardTarget ? ' keyboard-target-name' : ''}`}
      draggable={!readOnly}
      onDragStart={(e) => dragAndDrop.handleDragStart(e, row.id, 'row')}
      onDragOver={(e) => dragAndDrop.handleDragOver(e)}
      onDragEnter={markDropTarget}
      onDragLeave={unmarkDropTarget}
      onDrop={(e) => dragAndDrop.handleDrop(e, null, row.id)}
      data-row-id={row.id}
    >
      <div className="row-title">
        <button
          type="button"
          className="lane-toggle"
          aria-expanded={!collapsed}
          aria-label={laneLabel}
          title={laneLabel}
          onClick={() => onToggle(row.id)}
        >
          <Icon name="chevron-down" className="lane-chevron" />
        </button>
        {!readOnly && <span className="row-drag-handle" aria-hidden="true"><Icon name="grip" size="sm" /></span>}
        <EditableText
          id={row.id}
          text={row.name}
          onUpdate={updateRowName}
          className="row-name"
          inputClassName="row-name-input"
          type="row"
          disabled={readOnly}
        />
      </div>
      <div className="row-actions">
        <WipMeter count={row.taskCount} limit={row.wipLimit} state={row.wipState} />
        {!readOnly && (
          <button
            className="delete-row-btn icon-btn"
            title={t('row.delete')}
            aria-label={t('row.delete')}
            onClick={() => onDelete(row.id)}
          >
            <Icon name="trash" size="sm" />
          </button>
        )}
      </div>
    </td>
  );
}
