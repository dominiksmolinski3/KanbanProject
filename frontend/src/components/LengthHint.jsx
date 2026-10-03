import React from 'react';
import { useTranslation } from 'react-i18next';
import '../styles/components/LengthHint.css';

const SHOW_FROM = 0.9;

function LengthHint({ value, max }) {
  const { t } = useTranslation();
  const length = value ? value.length : 0;
  if (length < max * SHOW_FROM) {
    return null;
  }
  const atLimit = length >= max;
  return (
    <span className={`length-hint${atLimit ? ' at-limit' : ''}`} role="status">
      <span className="length-hint-meter" aria-hidden="true">
        <span style={{ width: `${Math.min(100, Math.round((length / max) * 100))}%` }} />
      </span>
      {atLimit
        ? t('forms.textLimit.atLimit', { max })
        : t('forms.textLimit.remaining', { count: max - length })}
    </span>
  );
}

export default LengthHint;
