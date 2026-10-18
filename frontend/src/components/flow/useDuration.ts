import { useCallback } from 'react';
import { useTranslation } from 'react-i18next';

export type FormatDuration = (hours: number | null | undefined) => string;

export function useDuration(): FormatDuration {
  const { t } = useTranslation();
  return useCallback((hours) => {
    if (hours === null || hours === undefined) return t('flow.none');
    if (hours < 48) return t('flow.hours', { count: Math.round(hours * 10) / 10 });
    return t('flow.days', { count: Math.round(hours / 2.4) / 10 });
  }, [t]);
}
