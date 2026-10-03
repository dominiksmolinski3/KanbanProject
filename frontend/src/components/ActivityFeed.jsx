import React, { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import { fetchActivity } from '../services/activityApi';
import Icon from './Icon';
import '../styles/components/ActivityFeed.css';

const MARK = '⁣';
const SEGMENT = new RegExp(`${MARK}(who|what|detail)${MARK}`);

const ICONS = {
  CREATED: 'plus',
  MOVED: 'arrow-right',
  ASSIGNED: 'user',
  UNASSIGNED: 'user',
  COMPLETED: 'check',
  REOPENED: 'reopen',
  DELETED: 'trash',
  COMMENTED: 'comment',
};

const dayKey = (date) => `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;

function dayLabel(date, language, t) {
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (dayKey(date) === dayKey(today)) return t('activity.today');
  if (dayKey(date) === dayKey(yesterday)) return t('activity.yesterday');
  try {
    return new Intl.DateTimeFormat(language || undefined, { weekday: 'long', day: 'numeric', month: 'long' }).format(date);
  } catch {
    return date.toLocaleDateString();
  }
}

function groupByDay(entries, language, t) {
  const days = [];
  for (const entry of entries) {
    const date = new Date(entry.occurredAt);
    const key = dayKey(date);
    let day = days[days.length - 1];
    if (!day || day.key !== key) {
      day = { key, label: dayLabel(date, language, t), entries: [] };
      days.push(day);
    }
    day.entries.push(entry);
  }
  return days;
}

function formatTime(value, language) {
  try {
    return new Intl.DateTimeFormat(language || undefined, { hour: '2-digit', minute: '2-digit' }).format(new Date(value));
  } catch {
    return new Date(value).toLocaleTimeString();
  }
}

function ActivityFeed() {
  const { activeBoardId } = useKanban();
  const { t, i18n } = useTranslation();

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

  useEffect(() => {
    setPage(0);
  }, [activeBoardId]);

  const describe = (entry) => {
    const values = {
      who: entry.actorName || t('activity.someone'),
      what: entry.taskTitle || t('activity.untitledTask'),
      detail: entry.detail || '',
    };
    const sentence = t(`activity.types.${entry.type}`, {
      who: `${MARK}who${MARK}`,
      what: `${MARK}what${MARK}`,
      detail: `${MARK}detail${MARK}`,
    });
    return sentence.split(SEGMENT).map((part, index) => {
      if (index % 2 === 0) return part;
      return <span key={index} className={`activity-seg-${part}`}>{values[part]}</span>;
    });
  };

  const days = groupByDay(entries, i18n?.language, t);

  return (
    <section className="activity-feed page-shell" data-testid="activity-feed">
      <header className="page-head">
        <h1 className="page-title">{t('activity.heading')}</h1>
        <p className="page-lede">{t('activity.explainer')}</p>
      </header>

      <div className="page-panel activity-panel">
        {loading && (
          <div className="page-empty" role="status">
            <span className="activity-spinner" aria-hidden="true" />
            <p className="page-empty-title">{t('activity.loading')}</p>
          </div>
        )}

        {!loading && failed && (
          <div className="page-empty" role="alert">
            <span className="page-empty-icon danger"><Icon name="warning" /></span>
            <p className="page-empty-title">{t('activity.failed')}</p>
          </div>
        )}

        {!loading && !failed && entries.length === 0 && (
          <div className="page-empty">
            <span className="page-empty-icon"><Icon name="activity" /></span>
            <p className="page-empty-title">{t('activity.empty')}</p>
          </div>
        )}

        {!loading && !failed && days.map((day) => (
          <div key={day.key} className="activity-day">
            <h2 className="activity-day-label">{day.label}</h2>
            <ol className="activity-list">
              {day.entries.map(entry => (
                <li key={entry.id} className={`activity-entry activity-${entry.type.toLowerCase()}`}>
                  <span className="activity-icon" aria-hidden="true">
                    <Icon name={ICONS[entry.type] || 'activity'} size="sm" />
                  </span>
                  <p className="activity-what">{describe(entry)}</p>
                  <time className="activity-when" dateTime={entry.occurredAt}>
                    {formatTime(entry.occurredAt, i18n?.language)}
                  </time>
                </li>
              ))}
            </ol>
          </div>
        ))}
      </div>

      {totalPages > 1 && (
        <nav className="page-pager activity-paging" aria-label={t('activity.heading')}>
          <button type="button" className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
            <Icon name="chevron-left" size="sm" />
            {t('activity.previous')}
          </button>
          <span className="activity-page-of">
            {t('activity.pageOf', { page: page + 1, pages: totalPages, total })}
          </span>
          <button
            type="button"
            className="btn btn-secondary btn-sm"
            disabled={page + 1 >= totalPages}
            onClick={() => setPage(page + 1)}
          >
            {t('activity.next')}
            <Icon name="chevron-right" size="sm" />
          </button>
        </nav>
      )}
    </section>
  );
}

export default ActivityFeed;
