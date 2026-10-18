import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import BoardActions from '../BoardActions';
import Icon from '../Icon';
import TaskSearch from '../TaskSearch';

export default function BoardToolbar({ dailyFocusCount }: { dailyFocusCount: number }) {
  const { t } = useTranslation();
  const { readOnly, dailyFocusOnly, setDailyFocusOnly } = useKanban();

  return (
    <div className="board-toolbar">
      {!readOnly && <BoardActions />}
      <button
        type="button"
        className={`daily-focus-filter ${dailyFocusOnly ? 'active' : ''}`}
        aria-pressed={dailyFocusOnly}
        onClick={() => setDailyFocusOnly(!dailyFocusOnly)}
      >
        <Icon name="star" size="sm" filled={dailyFocusOnly} /> {t('board.dailyFocus')}
        <span className="daily-focus-count">{dailyFocusCount}</span>
      </button>
      {dailyFocusOnly && dailyFocusCount === 0 && (
        <span className="daily-focus-empty">{t('board.dailyFocusEmpty')}</span>
      )}
      <TaskSearch />
    </div>
  );
}
