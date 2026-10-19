import { useTranslation } from 'react-i18next';
import Icon from '../Icon';

interface ActivityPagerProps {
  page: number;
  totalPages: number;
  total: number;
  onPage: (page: number) => void;
}

export default function ActivityPager({ page, totalPages, total, onPage }: ActivityPagerProps) {
  const { t } = useTranslation();

  return (
    <nav className="page-pager activity-paging" aria-label={t('activity.heading')}>
      <button type="button" className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => onPage(page - 1)}>
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
        onClick={() => onPage(page + 1)}
      >
        {t('activity.next')}
        <Icon name="chevron-right" size="sm" />
      </button>
    </nav>
  );
}
