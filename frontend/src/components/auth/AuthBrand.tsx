import { useTranslation } from 'react-i18next';
import BrandMark from '../BrandMark';

const MINI_BOARD = [
  { pips: [true, true, false, false], cards: [34, 22, 28] },
  { pips: [true, true, true, false], cards: [26, 38] },
  { pips: [true, true, true, true], cards: [22, 30, 24, 18] },
];

export default function AuthBrand() {
  const { t } = useTranslation();

  return (
    <section className="auth-brand" aria-label={t('board.title', 'Kanban Project')}>
      <div className="auth-brand-mark">
        <BrandMark className="auth-logo" />
        <span>{t('board.title', 'Kanban Project')}</span>
      </div>
      <div className="auth-claim-block">
        <h1 className="auth-claim">{t('auth.claim')}</h1>
        <p className="auth-claim-sub">{t('auth.claimSub')}</p>
      </div>
      <div className="auth-mini-board" aria-hidden="true">
        {MINI_BOARD.map((column, c) => (
          <div key={c} className="auth-mini-column">
            <span className="auth-mini-pips">
              {column.pips.map((on, p) => <i key={p} className={on ? 'on' : ''} />)}
            </span>
            {column.cards.map((height, k) => <span key={k} className="auth-mini-card" style={{ height }} />)}
          </div>
        ))}
      </div>
    </section>
  );
}
