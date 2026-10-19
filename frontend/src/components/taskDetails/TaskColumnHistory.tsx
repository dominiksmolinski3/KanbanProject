import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Id } from '../../types/api';
import Icon from '../Icon';
import HistoryPager from './history/HistoryPager';
import HistoryTimeline from './history/HistoryTimeline';
import TimeSpentStats from './history/TimeSpentStats';
import { useColumnHistory } from './history/useColumnHistory';

function TaskColumnHistory({ taskId }: { taskId: Id }) {
  const { t } = useTranslation();
  const { history, timeSpent, loadingTimeSpent } = useColumnHistory(taskId);
  const [page, setPage] = useState(0);

  return (
    <div className="column-history-section">
      <div className="section-header">
        <h3>{t('taskActions.columnHistory')}</h3>
      </div>
      <div className="column-history-content">
        {history.length === 0 ? (
          <div className="no-history">
            <Icon name="clock" size="lg" />
            <p>{t('taskDetails.noColumnHistory')}</p>
            <span>{t('taskDetails.noColumnHistoryHint')}</span>
          </div>
        ) : (
          <>
            <HistoryTimeline history={history} page={page} />
            <HistoryPager page={page} total={history.length} onPage={setPage} />
            <TimeSpentStats timeSpent={timeSpent} loading={loadingTimeSpent} />
          </>
        )}
      </div>
    </div>
  );
}

export default TaskColumnHistory;
