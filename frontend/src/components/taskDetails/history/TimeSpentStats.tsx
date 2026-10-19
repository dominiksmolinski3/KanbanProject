import { useTranslation } from 'react-i18next';
import type { ColumnTimeSpent } from '../../../types/api';
import Icon from '../../Icon';
import { formatDuration } from './historyFormat';

interface TimeSpentStatsProps {
  timeSpent: ColumnTimeSpent[];
  loading: boolean;
}

export default function TimeSpentStats({ timeSpent, loading }: TimeSpentStatsProps) {
  const { t, i18n } = useTranslation();
  const language = i18n.resolvedLanguage;
  const maxTime = Math.max(...timeSpent.map(column => column.totalTimeMs));

  return (
    <div className="column-time-stats">
      <h3>{t('taskDetails.timeSpentAnalysis')}</h3>
      {loading ? (
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
  );
}
