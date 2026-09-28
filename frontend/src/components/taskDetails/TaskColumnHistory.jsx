import React, { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { fetchColumns, getTaskColumnHistory, getTaskColumnTimeSpentSummary } from '../../services/api';

const PAGE_SIZE = 4;
const MAX_VISIBLE_PAGES = 5;

const formatDuration = (from, to) => {
  const diffMs = Math.max(0, new Date(to.changedAt) - new Date(from.changedAt));
  const days = Math.floor(diffMs / (1000 * 60 * 60 * 24));
  const hours = Math.floor((diffMs % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60));
  const minutes = Math.floor((diffMs % (1000 * 60 * 60)) / (1000 * 60));

  if (days > 0) return `${days}d ${hours}h`;
  if (hours > 0) return `${hours}h ${minutes}m`;
  if (minutes > 0) return `${minutes}m`;
  return '< 1m';
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

  const columns = await fetchColumns().catch(() => []);
  const columnNames = Object.fromEntries((columns || []).map(column => [column.id, column.name]));

  return history
    .map(item => ({
      ...item,
      columnName: item.columnName || columnNames[item.columnId] || item.column_name || `Column ${item.columnId}`
    }))
    .filter((item, index, all) => all.findIndex(other =>
      Math.abs(new Date(other.changedAt) - new Date(item.changedAt)) < 1000 &&
      other.columnId === item.columnId
    ) === index);
}

function TaskColumnHistory({ taskId }) {
  const { t } = useTranslation();
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
          <h4>{t('taskActions.columnHistory')}</h4>
        </div>
        <div className="column-history-content">
          <div className="no-history">
            <svg width="64" height="64" viewBox="0 0 24 24" fill="currentColor">
              <path d="M12,2A10,10 0 0,0 2,12A10,10 0 0,0 12,22A10,10 0 0,0 22,12A10,10 0 0,0 12,2M16.2,16.2L11,13V7H12.5V12.2L17,14.9L16.2,16.2Z"/>
            </svg>
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
        <h4>{t('taskActions.columnHistory')}</h4>
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
                    {isCurrent && <span className="current-badge">{t('taskDetails.startBadge')}</span>}
                    {isStart && <span className="start-badge">{t('taskDetails.currentBadge')}</span>}
                  </div>
                  <div className="timeline-date">
                    {t('taskDetails.dateAtTime', {
                      date: changedAt.toLocaleDateString(),
                      time: changedAt.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
                    })}
                  </div>
                  {!isCurrent && (
                    <div className="timeline-duration">
                      {t('taskDetails.duration')} {formatDuration(item, history[globalIndex + 1])}
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
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
              <path d="M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z"/>
            </svg>
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
            <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
              <path d="M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z"/>
            </svg>
          </button>
        </div>

        <div className="column-time-stats">
          <h5>{t('taskDetails.timeSpentAnalysis')}</h5>
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
                    <span className="stat-column-name">{column.columnName}</span>
                    <span className="stat-time">{column.formattedTime}</span>
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
              <svg width="48" height="48" viewBox="0 0 24 24" fill="currentColor">
                <path d="M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z"/>
              </svg>
              <p>{t('taskDetails.noTimeStatistics')}</p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default TaskColumnHistory;
