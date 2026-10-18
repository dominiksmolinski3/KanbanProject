import { useTranslation } from 'react-i18next';

interface SearchPagerProps {
  page: number;
  totalPages: number;
  hasResults: boolean;
  onPage: (page: number) => void;
}

export default function SearchPager({ page, totalPages, hasResults, onPage }: SearchPagerProps) {
  const { t } = useTranslation();

  return (
    <div className="task-search-pager">
      <button type="button" disabled={page === 0} onClick={() => onPage(page - 1)}>
        {t('board.search.previous')}
      </button>
      <span>{t('board.search.pageOf', { page: page + 1, pages: totalPages })}</span>
      <button
        type="button"
        disabled={!hasResults || page + 1 >= totalPages}
        onClick={() => onPage(page + 1)}
      >
        {t('board.search.next')}
      </button>
    </div>
  );
}
