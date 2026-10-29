import { useTranslation } from 'react-i18next';
import type { User } from '../../types/api';
import ChipFacet from './ChipFacet';
import type { SearchFilters } from './useSearchFilters';

interface SearchFacetsProps {
  filters: SearchFilters;
  labels: string[];
  users: User[];
}

export default function SearchFacets({ filters, labels, users }: SearchFacetsProps) {
  const { t } = useTranslation();

  return (
    <div className="task-search-facets">
      {labels.length > 0 && (
        <ChipFacet
          legend={t('board.search.labels')}
          options={labels.map((label) => ({ value: label, label }))}
          selected={filters.labels}
          onToggle={filters.toggleLabel}
        />
      )}

      {users.length > 0 && (
        <ChipFacet
          legend={t('board.search.assignees')}
          options={users.map((user) => ({ value: user.id, label: user.name || user.email }))}
          selected={filters.assignees}
          onToggle={filters.toggleAssignee}
        />
      )}

      <fieldset className="task-search-facet">
        <legend>{t('board.search.status')}</legend>
        <select
          className="task-search-status"
          data-testid="task-search-status"
          aria-label={t('board.search.status')}
          value={filters.completed}
          onChange={(e) => filters.changeCompleted(e.target.value)}
        >
          <option value="">{t('board.search.statusAny')}</option>
          <option value="false">{t('board.search.statusOpen')}</option>
          <option value="true">{t('board.search.statusCompleted')}</option>
        </select>
      </fieldset>

      <fieldset className="task-search-facet">
        <legend>{t('board.search.deadline')}</legend>
        <label className="task-search-date">
          <span>{t('board.search.deadlineFrom')}</span>
          <input
            type="datetime-local"
            value={filters.deadlineFrom}
            onChange={(e) => filters.changeDeadlineFrom(e.target.value)}
          />
        </label>
        <label className="task-search-date">
          <span>{t('board.search.deadlineTo')}</span>
          <input
            type="datetime-local"
            value={filters.deadlineTo}
            onChange={(e) => filters.changeDeadlineTo(e.target.value)}
          />
        </label>
      </fieldset>
    </div>
  );
}
