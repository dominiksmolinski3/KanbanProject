import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import Icon from '../Icon';
import { fetchColumns, getTaskColumnHistory, getTaskColumnTimeSpentSummary } from '../../services/api';

const PAGE_SIZE = 4;
const MAX_VISIBLE_PAGES = 5;

const MINUTE_MS = 60 * 1000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

const formatDuration = (ms, t, language) => {
  if (ms < MINUTE_MS) return t('taskDetails.underAMinute');
  const days = Math.floor(ms / DAY_MS);
  const hours = Math.floor((ms % DAY_MS) / HOUR_MS);
  const minutes = Math.floor((ms % HOUR_MS) / MINUTE_MS);
  const parts = days > 0 ? [[days, 'day'], [hours, 'hour']] : [[hours, 'hour'], [minutes, 'minute']];
  const units = parts
    .filter(([value]) => value > 0)
    .map(([value, unit]) => new Intl.NumberFormat(language, { style: 'unit', unit, unitDisplay: 'narrow' }).format(value));
  return new Intl.ListFormat(language, { style: 'narrow', type: 'unit' }).format(units);
};

const visiblePages = (totalPages, current) => {
  if (totalPages <= MAX_VISIBLE_PAGES) {
    return Array.from({ length: totalPages }, (_, i) => i);
  }

  let start = Math.max(0, current - 2);
  const end = Math.min(totalPages - 1, start + MAX_VISIBLE_PAGES - 1);
  if (end - start < MAX_VISIBLE_PAGES - 1) {
    start = Math.max(0, end - MAX_VISIBLE_PAGES + 1);
  }

  const pages = [];
  if (start > 0) {
    pages.push(0);
    if (start > 1) pages.push('start-ellipsis');
  }
  for (let i = start; i <= end; i++) {
    pages.push(i);
  }
  if (end < totalPages - 1) {
    if (end < totalPages - 2) pages.push('end-ellipsis');
    pages.push(totalPages - 1);
  }
  return pages;
};

async function loadHistory(taskId) {
  const history = await getTaskColumnHistory(taskId);
  if (!history || history.length === 0) {
    return [];
  }

  let columns;
  try {
    columns = (await fetchColumns()) || [];
  } catch {
    columns = [];
  }
  const columnNames = Object.fromEntries(columns.map(column => [column.id, column.name]));

  return history
    .map(item => ({
      ...item,
      columnName: item.columnName || columnNames[item.columnId]
    }))
    .filter((item, index, all) => all.findIndex(other =>
      Math.abs(new Date(other.changedAt) - new Date(item.changedAt)) < 1000 &&
      other.columnId === item.columnId
    ) === index);
}

function TaskColumnHistory({ taskId }) {
  const { t, i18n } = useTranslation();
  const language = i18n.resolvedLanguage;
  const [history, setHistory] = useState([]);
  const [page, setPage] = useState(0);
  const [timeSpent, setTimeSpent] = useState([]);
  const [loadingTimeSpent, setLoadingTimeSpent] = useState(true);

  useEffect(() => {
    let live = true;
    (async () => {
      try {
        const data = await loadHistory(taskId);
        if (live) setHistory(data);
      } catch (error) {
        console.error('Error fetching column history:', error);
        if (live) setHistory([]);
      }
    })();
    (async () => {
      try {
        const data = await getTaskColumnTimeSpentSummary(taskId);
        if (live) setTimeSpent(data || []);
      } catch (error) {
        console.error('Error loading column time spent:', error);
      } finally {
        if (live) setLoadingTimeSpent(false);
      }
    })();
    return () => {
      live = false;
    };
  }, [taskId]);

  if (history.length === 0) {
    return (
      <div className="column-history-section">
        <div className="section-header">
          <h3>{t('taskActions.columnHistory')}</h3>
        </div>
        <div className="column-history-content">
          <div className="no-history">
            <Icon name="clock" size="lg" />
            <p>{t('taskDetails.noColumnHistory')}</p>
            <span>{t('taskDetails.noColumnHistoryHint')}</span>
          </div>
        </div>
      </div>
    );
  }

  const totalPages = Math.ceil(history.length / PAGE_SIZE);
  const maxTime = Math.max(...timeSpent.map(c => c.totalTimeMs));

  return (
    <div className="column-history-section">
      <div className="section-header">
        <h3>{t('taskActions.columnHistory')}</h3>
      </div>
      <div className="column-history-content">
        <div className="timeline-container">
          <div className="timeline-line"></div>
          {history.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).map((item, index) => {
            const globalIndex = page * PAGE_SIZE + index;
            const isStart = globalIndex === 0;
            const isCurrent = globalIndex === history.length - 1;
            const changedAt = new Date(item.changedAt);

            return (
              <div key={item.id} className={`timeline-item ${isStart ? 'timeline-start' : ''} ${isCurrent ? 'timeline-current' : ''}`}>
                <div className="timeline-marker">
                  <div className="timeline-dot"></div>
                  {!isCurrent && <div className="timeline-connector"></div>}
                </div>
                <div className="timeline-content">
                  <div className="timeline-column-name">
                    {item.columnName || t('taskDetails.unknownColumn')}
                    {isCurrent && <span className="current-badge">{t('taskDetails.currentBadge')}</span>}
                    {isStart && <span className="start-badge">{t('taskDetails.startBadge')}</span>}
                  </div>
                  <div className="timeline-date">
                    {t('taskDetails.dateAtTime', {
                      date: changedAt.toLocaleDateString(language),
                      time: changedAt.toLocaleTimeString(language, { hour: '2-digit', minute: '2-digit' })
                    })}
                  </div>
                  {!isCurrent && (
                    <div className="timeline-duration">
                      {t('taskDetails.duration', {
                        duration: formatDuration(
                          Math.max(0, new Date(history[globalIndex + 1].changedAt) - changedAt), t, language)
                      })}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        <div className="history-progress">
          <div className="progress-bar">
            <div className="progress-fill" style={{ width: `${((page + 1) / totalPages) * 100}%` }}></div>
          </div>
          <div className="progress-text">
            {t('taskDetails.showingMoves', {
              from: page * PAGE_SIZE + 1,
              to: Math.min((page + 1) * PAGE_SIZE, history.length),
              total: history.length
            })}
          </div>
        </div>

        <div className="history-navigation">
          <button
            className="nav-btn nav-btn-prev"
            disabled={page === 0}
            onClick={() => setPage(prev => Math.max(0, prev - 1))}
          >
            <Icon name="chevron-left" size="sm" />
            {t('taskDetails.previous')}
          </button>

          <div className="page-indicators">
            {visiblePages(totalPages, page).map(entry => (typeof entry === 'string' ? (
              <span key={entry} className="page-ellipsis">...</span>
            ) : (
              <button
                key={entry}
                className={`page-dot ${entry === page ? 'active' : ''}`}
                onClick={() => setPage(entry)}
                title={t('taskDetails.page', { number: entry + 1 })}
              />
            )))}
          </div>

          <button
            className="nav-btn nav-btn-next"
            disabled={page + 1 >= totalPages}
            onClick={() => setPage(prev => Math.min(totalPages - 1, prev + 1))}
          >
            {t('taskDetails.next')}
            <Icon name="chevron-right" size="sm" />
          </button>
        </div>

        <div className="column-time-stats">
          <h3>{t('taskDetails.timeSpentAnalysis')}</h3>
          {loadingTimeSpent ? (
            <div className="loading-stats">
              <div className="loading-spinner"></div>
              <span>{t('taskDetails.loadingTimeStatistics')}</span>
            </div>
          ) : timeSpent.length > 0 ? (
            <div className="time-stats-container">
              {timeSpent.map((column, index) => (
                <div key={column.columnId} className="time-stat-item">
                  <div className="stat-header">
                    <span className="stat-column-name">{column.columnName || t('taskDetails.unknownColumn')}</span>
                    <span className="stat-time">{formatDuration(column.totalTimeMs, t, language)}</span>
                  </div>
                  <div className="stat-bar-container">
                    <div
                      className="stat-bar"
                      style={{
                        width: `${(column.totalTimeMs / maxTime) * 100}%`,
                        animationDelay: `${index * 0.1}s`
                      }}
                    ></div>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="no-stats">
              <Icon name="flow" size="lg" />
              <p>{t('taskDetails.noTimeStatistics')}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default TaskColumnHistory;
