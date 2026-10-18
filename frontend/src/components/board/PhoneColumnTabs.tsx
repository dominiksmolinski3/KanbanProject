import { useTranslation } from 'react-i18next';
import type { WipDecoration } from '../../board/boardModel';
import type { ColumnSummary } from '../../types/api';

interface PhoneColumnTabsProps {
  columns: (ColumnSummary & WipDecoration)[];
  activeId: number | undefined;
  onSelect: (columnId: number) => void;
}

export default function PhoneColumnTabs({ columns, activeId, onSelect }: PhoneColumnTabsProps) {
  const { t } = useTranslation();

  return (
    <div className="phone-column-tabs" role="tablist" aria-label={t('forms.addRowColumn.tabs.columns')}>
      {columns.map((column) => (
        <button
          key={column.id}
          type="button"
          role="tab"
          aria-selected={column.id === activeId}
          className={`phone-column-tab wip-${column.wipState}`}
          onClick={() => onSelect(column.id)}
        >
          {column.name}
          <span className="phone-column-count">
            {column.wipLimit > 0 ? `${column.taskCount}/${column.wipLimit}` : column.taskCount}
          </span>
        </button>
      ))}
    </div>
  );
}
