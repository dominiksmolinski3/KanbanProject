import { useTranslation } from 'react-i18next';
import type { TaskColumnHistoryEntry } from '../../../types/api';
import { PAGE_SIZE, formatDuration } from './historyFormat';

interface HistoryTimelineProps {
  history: TaskColumnHistoryEntry[];
  page: number;
}

export default function HistoryTimeline({ history, page }: HistoryTimelineProps) {
  const { t, i18n } = useTranslation();
  const language = i18n.resolvedLanguage;

  return (
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
                      Math.max(0, new Date(history[globalIndex + 1].changedAt).getTime() - changedAt.getTime()), t, language)
                  })}
                </div>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
