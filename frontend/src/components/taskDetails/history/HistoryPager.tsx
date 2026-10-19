import { useTranslation } from 'react-i18next';
import Icon from '../../Icon';
import { PAGE_SIZE, visiblePages } from './historyFormat';

interface HistoryPagerProps {
  page: number;
  total: number;
  onPage: (page: number) => void;
}

export default function HistoryPager({ page, total, onPage }: HistoryPagerProps) {
  const { t } = useTranslation();
  const totalPages = Math.ceil(total / PAGE_SIZE);

  return (
    <>
      <div className="history-progress">
        <div className="progress-bar">
          <div className="progress-fill" style={{ width: `${((page + 1) / totalPages) * 100}%` }}></div>
        </div>
        <div className="progress-text">
          {t('taskDetails.showingMoves', {
            from: page * PAGE_SIZE + 1,
            to: Math.min((page + 1) * PAGE_SIZE, total),
            total
          })}
        </div>
      </div>

      <div className="history-navigation">
        <button className="nav-btn nav-btn-prev" disabled={page === 0} onClick={() => onPage(Math.max(0, page - 1))}>
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
              onClick={() => onPage(entry)}
              title={t('taskDetails.page', { number: entry + 1 })}
            />
          )))}
        </div>

        <button
          className="nav-btn nav-btn-next"
          disabled={page + 1 >= totalPages}
          onClick={() => onPage(Math.min(totalPages - 1, page + 1))}
        >
          {t('taskDetails.next')}
          <Icon name="chevron-right" size="sm" />
        </button>
      </div>
    </>
  );
}
