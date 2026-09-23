import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import { searchTasks, getAllLabels, fetchUsers } from '../services/api';
import '../styles/components/TaskSearch.css';

function TaskSearch() {
  const { columns, rows, tasks } = useKanban();
  const { t } = useTranslation();

  const [isOpen, setIsOpen] = useState(false);
  const [term, setTerm] = useState('');
  const [selectedLabels, setSelectedLabels] = useState([]);
  const [selectedAssignees, setSelectedAssignees] = useState([]);
  const [completed, setCompleted] = useState('');
  const [deadlineFrom, setDeadlineFrom] = useState('');
  const [deadlineTo, setDeadlineTo] = useState('');
  const [page, setPage] = useState(0);

  const [labels, setLabels] = useState([]);
  const [users, setUsers] = useState([]);
  const [results, setResults] = useState(null);
  const [isSearching, setIsSearching] = useState(false);
  const [failed, setFailed] = useState(false);

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

  useEffect(() => {
    if (!isOpen) {
      return;
    }
    let cancelled = false;
    Promise.all([getAllLabels(), fetchUsers()])
      .then(([boardLabels, boardUsers]) => {
        if (!cancelled) {
          setLabels(Array.isArray(boardLabels) ? boardLabels : []);
          setUsers(Array.isArray(boardUsers) ? boardUsers : []);
        }
      })
      .catch(() => {
      });
    return () => {
      cancelled = true;
    };
  }, [isOpen]);

  const latestRequest = useRef(0);

  useEffect(() => {
    if (!isOpen) {
      return undefined;
    }
    const sequence = latestRequest.current + 1;
    latestRequest.current = sequence;

    const timer = setTimeout(() => {
      setIsSearching(true);
      searchTasks({
        q: term,
        labels: selectedLabels,
        assignees: selectedAssignees,
        completed: completed === '' ? null : completed === 'true',
        deadlineFrom: deadlineFrom || null,
        deadlineTo: deadlineTo || null,
        page
      })
        .then((found) => {
          if (latestRequest.current === sequence) {
            setResults(found);
            setFailed(false);
          }
        })
        .catch(() => {
          if (latestRequest.current === sequence) {
            setResults(null);
            setFailed(true);
          }
        })
        .finally(() => {
          if (latestRequest.current === sequence) {
            setIsSearching(false);
          }
        });
    }, 300);

    return () => clearTimeout(timer);
  }, [isOpen, term, selectedLabels, selectedAssignees, completed, deadlineFrom, deadlineTo, page]);

  const changeFilter = useCallback((apply) => {
    setPage(0);
    apply();
  }, []);

  const toggle = (list, setList, value) =>
    changeFilter(() =>
      setList(list.includes(value) ? list.filter((entry) => entry !== value) : [...list, value])
    );

  const clearFilters = () =>
    changeFilter(() => {
      setTerm('');
      setSelectedLabels([]);
      setSelectedAssignees([]);
      setCompleted('');
      setDeadlineFrom('');
      setDeadlineTo('');
    });

  const hasFilters =
    term !== '' ||
    selectedLabels.length > 0 ||
    selectedAssignees.length > 0 ||
    completed !== '' ||
    deadlineFrom !== '' ||
    deadlineTo !== '';

  const showOnBoard = (taskId) => {
    const card = document.getElementById(`task-${taskId}`);
    if (!card) {
      return false;
    }
    card.scrollIntoView({ behavior: 'smooth', block: 'center' });
    card.classList.add('task-search-hit');
    setTimeout(() => card.classList.remove('task-search-hit'), 2000);
    return true;
  };

  const [missing, setMissing] = useState(null);

  const onShowOnBoard = (taskId) => {
    setMissing(showOnBoard(taskId) ? null : taskId);
  };

  const cellOf = (task) => {
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
        🔍 {t('board.search.open')}
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
          ×
        </button>
      </div>

      <input
        type="search"
        className="task-search-term"
        data-testid="task-search-term"
        value={term}
        placeholder={t('board.search.placeholder')}
        onChange={(e) => changeFilter(() => setTerm(e.target.value))}
      />

      <div className="task-search-facets">
        {labels.length > 0 && (
          <fieldset className="task-search-facet">
            <legend>{t('board.search.labels')}</legend>
            <div className="task-search-chips">
              {labels.map((label) => (
                <button
                  type="button"
                  key={label}
                  className={`task-search-chip ${selectedLabels.includes(label) ? 'active' : ''}`}
                  aria-pressed={selectedLabels.includes(label)}
                  onClick={() => toggle(selectedLabels, setSelectedLabels, label)}
                >
                  {label}
                </button>
              ))}
            </div>
          </fieldset>
        )}

        {users.length > 0 && (
          <fieldset className="task-search-facet">
            <legend>{t('board.search.assignees')}</legend>
            <div className="task-search-chips">
              {users.map((user) => (
                <button
                  type="button"
                  key={user.id}
                  className={`task-search-chip ${selectedAssignees.includes(user.id) ? 'active' : ''}`}
                  aria-pressed={selectedAssignees.includes(user.id)}
                  onClick={() => toggle(selectedAssignees, setSelectedAssignees, user.id)}
                >
                  {user.name || user.email}
                </button>
              ))}
            </div>
          </fieldset>
        )}

        <fieldset className="task-search-facet">
          <legend>{t('board.search.status')}</legend>
          <select
            className="task-search-status"
            data-testid="task-search-status"
            value={completed}
            onChange={(e) => changeFilter(() => setCompleted(e.target.value))}
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
              value={deadlineFrom}
              onChange={(e) => changeFilter(() => setDeadlineFrom(e.target.value))}
            />
          </label>
          <label className="task-search-date">
            <span>{t('board.search.deadlineTo')}</span>
            <input
              type="datetime-local"
              value={deadlineTo}
              onChange={(e) => changeFilter(() => setDeadlineTo(e.target.value))}
            />
          </label>
        </fieldset>
      </div>

      {hasFilters && (
        <button type="button" className="task-search-clear" onClick={clearFilters}>
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
          <li key={task.id} className="task-search-result">
            <div className="task-search-result-main">
              <span className={`task-search-result-title ${task.completed ? 'completed' : ''}`}>
                {task.title}
              </span>
              <span className="task-search-result-cell">{cellOf(task)}</span>
            </div>
            <div className="task-search-result-meta">
              {(task.labels || []).map((label) => (
                <span key={label} className="task-search-result-label">
                  {label}
                </span>
              ))}
              {(task.userIds || []).map((userId) => (
                <span key={userId} className="task-search-result-user">
                  {userNames[userId] || `#${userId}`}
                </span>
              ))}
              {task.deadline && (
                <span className={`task-search-result-deadline ${task.expired ? 'expired' : ''}`}>
                  {task.deadline.replace('T', ' ').slice(0, 16)}
                </span>
              )}
            </div>
            <button
              type="button"
              className="task-search-locate"
              onClick={() => onShowOnBoard(task.id)}
            >
              {t('board.search.showOnBoard')}
            </button>
            {missing === task.id && (
              <span className="task-search-missing">{t('board.search.notOnBoard')}</span>
            )}
          </li>
        ))}
      </ul>

      {totalPages > 1 && (
        <div className="task-search-pager">
          <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>
            {t('board.search.previous')}
          </button>
          <span>{t('board.search.pageOf', { page: page + 1, pages: totalPages })}</span>
          <button
            type="button"
            disabled={!results || page + 1 >= totalPages}
            onClick={() => setPage(page + 1)}
          >
            {t('board.search.next')}
          </button>
        </div>
      )}

      {results && total === 0 && tasks.length > 0 && (
        <p className="task-search-hint">{t('board.search.boardHas', { count: tasks.length })}</p>
      )}
    </div>
  );
}

export default TaskSearch;
