import React from 'react';
import { useTranslation } from 'react-i18next';

function WipMeter({ count, limit, state }) {
  const { t } = useTranslation();
  const hasLimit = limit > 0;
  const label = hasLimit
    ? t(`board.wip.${state}`, { n: count, limit })
    : t('board.wip.count', { n: count });

  return (
    <span className={`wip-meter wip-${state}`} title={label}>
      <span className="visually-hidden">{label}</span>
      <span className="task-count" aria-hidden="true">{count}</span>
      {hasLimit && (
        <span
          className={`wip-limit${state === 'over' ? ' exceeded' : ''}${state === 'near' ? ' near' : ''}`}
          aria-hidden="true"
        >
          /{limit}
        </span>
      )}
    </span>
  );
}

export default WipMeter;
