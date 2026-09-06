import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import { fetchActivity } from '../services/activityApi';
import '../styles/components/ActivityFeed.css';

/**
 * What has happened on this board, newest first.
 *
 * <p>The board itself answers "where is everything now" and says nothing about how it got there.
 * `TaskColumnHistory` recorded moves and only moves, was never surfaced anywhere except as a bar
 * chart in the task panel, and has never known <em>who</em>. This is the other question.
 *
 * <p><b>The sentences are built here, not on the server.</b> The API returns a type name and a
 * detail string; the wording is a translation key, because a feed whose text was composed in Java
 * would be a screen the other eight languages cannot translate. That is the same reason the entry
 * carries a copy of the task's title rather than a rendered phrase.
 */
function ActivityFeed() {
  const { activeBoardId } = useKanban();
  const { t } = useTranslation();

  const [entries, setEntries] = useState([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setFailed(false);
    try {
      const results = await fetchActivity({ boardId: activeBoardId, page });
      setEntries(results.activities);
      setTotalPages(results.totalPages);
      setTotal(results.totalEntries);
    } catch (err) {
      console.error('Error fetching the activity feed:', err);
      setFailed(true);
      setEntries([]);
    } finally {
      setLoading(false);
    }
  }, [activeBoardId, page]);

  useEffect(() => {
    load();
  }, [load]);

  // Switching boards resets to the first page. A page number that survives the switch points into
  // a different feed of a different length, which is the single most common bug in a paged list.
  useEffect(() => {
    setPage(0);
  }, [activeBoardId]);

  const describe = (entry) => {
    const who = entry.actorName || t('activity.someone');
    const what = entry.taskTitle || t('activity.untitledTask');
    return t(`activity.types.${entry.type}`, { who, what, detail: entry.detail || '' });
  };

  return (
    <section className="activity-feed" data-testid="activity-feed">
      <header className="activity-header">
        <h2>{t('activity.heading')}</h2>
        <p className="activity-explainer">{t('activity.explainer')}</p>
      </header>

      {loading && <p className="activity-empty">{t('activity.loading')}</p>}

      {!loading && failed && <p className="activity-empty">{t('activity.failed')}</p>}

      {!loading && !failed && entries.length === 0 && (
        <p className="activity-empty">{t('activity.empty')}</p>
      )}

      {!loading && !failed && entries.length > 0 && (
        <ol className="activity-list">
          {entries.map(entry => (
            <li key={entry.id} className={`activity-entry activity-${entry.type.toLowerCase()}`}>
              <span className="activity-what">{describe(entry)}</span>
              <time className="activity-when" dateTime={entry.occurredAt}>
                {new Date(entry.occurredAt).toLocaleString()}
              </time>
            </li>
          ))}
        </ol>
      )}

      {totalPages > 1 && (
        <nav className="activity-paging">
          <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)}>
            {t('activity.previous')}
          </button>
          <span className="activity-page-of">
            {t('activity.pageOf', { page: page + 1, pages: totalPages, total })}
          </span>
          <button
            type="button"
            disabled={page + 1 >= totalPages}
            onClick={() => setPage(page + 1)}
          >
            {t('activity.next')}
          </button>
        </nav>
      )}
    </section>
  );
}

export default ActivityFeed;
