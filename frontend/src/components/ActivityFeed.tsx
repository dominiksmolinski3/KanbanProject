import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import Icon from './Icon';
import ActivityEntry from './activity/ActivityEntry';
import ActivityPager from './activity/ActivityPager';
import { groupByDay } from './activity/activityText';
import { useActivityFeed } from './activity/useActivityFeed';
import '../styles/components/ActivityFeed.css';

function ActivityFeed() {
  const { activeBoardId } = useKanban();
  const { t, i18n } = useTranslation();
  const { entries, page, setPage, totalPages, total, loading, failed } = useActivityFeed(activeBoardId);

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
              {day.entries.map((entry) => <ActivityEntry key={entry.id} entry={entry} />)}
            </ol>
          </div>
        ))}
      </div>

      {totalPages > 1 && <ActivityPager page={page} totalPages={totalPages} total={total} onPage={setPage} />}
    </section>
  );
}

export default ActivityFeed;
