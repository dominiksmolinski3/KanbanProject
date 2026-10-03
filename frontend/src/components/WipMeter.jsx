import React from 'react';
import { useTranslation } from 'react-i18next';

const MAX_PIPS = 12;

function WipMeter({ count, limit, state }) {
  const { t } = useTranslation();
  const hasLimit = limit > 0;
  const label = hasLimit
    ? t(`board.wip.${state}`, { n: count, limit })
    : t('board.wip.count', { n: count });
  const pips = hasLimit && limit <= MAX_PIPS
    ? Array.from({ length: Math.max(limit, Math.min(count, MAX_PIPS)) }, (_, i) => {
      if (i >= limit) return 'wip-pip extra';
      return i < count ? 'wip-pip on' : 'wip-pip';
    })
    : [];

  return (
    <span className={`wip-meter wip-${state}`} title={label}>
      <span className="visually-hidden">{label}</span>
      {pips.length > 0 && (
        <span className="wip-pips" aria-hidden="true">
          {pips.map((pip, i) => <i key={i} className={pip} />)}
        </span>
      )}
      {hasLimit && limit > MAX_PIPS && (
        <span className="wip-track" aria-hidden="true">
          <span style={{ width: `${Math.min(100, Math.round((count / limit) * 100))}%` }} />
        </span>
      )}
      <span className="wip-text" aria-hidden="true">
        <span className="task-count">{count}</span>
        {hasLimit && (
          <span
            className={`wip-limit${state === 'over' ? ' exceeded' : ''}${state === 'near' ? ' near' : ''}`}
          >
            /{limit}
          </span>
        )}
      </span>
    </span>
  );
}

export default WipMeter;
