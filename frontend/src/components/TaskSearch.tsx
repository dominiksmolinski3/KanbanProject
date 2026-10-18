import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Icon from './Icon';
import { useKanban } from '../context/KanbanContext';
import type { Task } from '../types/api';
import SearchFacets from './search/SearchFacets';
import SearchPager from './search/SearchPager';
import SearchResult from './search/SearchResult';
import { useFacetOptions } from './search/useFacetOptions';
import { useSearchFilters } from './search/useSearchFilters';
import { useShowOnBoard } from './search/useShowOnBoard';
import { useTaskSearch } from './search/useTaskSearch';
import '../styles/components/TaskSearch.css';

function TaskSearch() {
  const { columns, rows, tasks } = useKanban();
  const { t } = useTranslation();

  const [isOpen, setIsOpen] = useState(false);
  const filters = useSearchFilters();
  const { labels, users } = useFacetOptions(isOpen);
  const { results, isSearching, failed } = useTaskSearch(isOpen, filters);
  const onBoard = useShowOnBoard();

  const columnNames = useMemo(
    () => Object.fromEntries(columns.map((column) => [column.id, column.name])),
    [columns]
  );
  const rowNames = useMemo(
    () => Object.fromEntries(rows.map((row) => [row.id, row.name])),
    [rows]
  );
  const userNames = useMemo(
    () => Object.fromEntries(users.map((user) => [user.id, user.name || user.email])),
    [users]
  );

  const cellOf = (task: Task) => {
    const column = task.columnId ? columnNames[task.columnId] : null;
    const row = task.rowId ? rowNames[task.rowId] : null;
    return [column || t('board.search.noColumn'), row || t('board.search.noRow')].join(' · ');
  };

  if (!isOpen) {
    return (
      <button
        type="button"
        className="task-search-toggle"
        data-testid="open-task-search"
        onClick={() => setIsOpen(true)}
      >
        <Icon name="search" size="sm" />
        {t('board.search.open')}
      </button>
    );
  }

  const shown = results ? results.tasks.length : 0;
  const total = results ? results.totalTasks : 0;
  const totalPages = results ? results.totalPages : 0;

  return (
    <div className="task-search" data-testid="task-search">
      <div className="task-search-head">
        <h3>{t('board.search.title')}</h3>
        <button
          type="button"
          className="task-search-close"
          onClick={() => setIsOpen(false)}
          aria-label={t('board.search.close')}
        >
          <Icon name="close" />
        </button>
      </div>

      <input
        type="search"
        className="task-search-term"
        data-testid="task-search-term"
        value={filters.term}
        placeholder={t('board.search.placeholder')}
        onChange={(e) => filters.changeTerm(e.target.value)}
      />

      <SearchFacets filters={filters} labels={labels} users={users} />

      {filters.hasFilters && (
        <button type="button" className="task-search-clear" onClick={filters.clear}>
          {t('board.search.clear')}
        </button>
      )}

      <div className="task-search-summary" aria-live="polite">
        {isSearching && <span>{t('board.search.searching')}</span>}
        {!isSearching && failed && (
          <span className="task-search-failed">{t('board.search.error')}</span>
        )}
        {!isSearching && !failed && results && (
          <span>{t('board.search.count', { shown, total })}</span>
        )}
      </div>

      {!isSearching && !failed && results && results.tasks.length === 0 && (
        <p className="task-search-empty">{t('board.search.empty')}</p>
      )}

      <ul className="task-search-results">
        {(results ? results.tasks : []).map((task) => (
          <SearchResult
            key={task.id}
            task={task}
            cell={cellOf(task)}
            userName={(userId) => userNames[userId] || `#${userId}`}
            missing={onBoard.missing === task.id}
            onShow={onBoard.show}
          />
        ))}
      </ul>

      {totalPages > 1 && (
        <SearchPager page={filters.page} totalPages={totalPages} hasResults={Boolean(results)} onPage={filters.setPage} />
      )}

      {results && total === 0 && tasks.length > 0 && (
        <p className="task-search-hint">{t('board.search.boardHas', { count: tasks.length })}</p>
      )}
    </div>
  );
}

export default TaskSearch;
