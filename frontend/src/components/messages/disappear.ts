import type { TFunction } from 'i18next';

export const DISAPPEAR_OPTIONS = [0, 3600, 86400, 604800, 2592000] as const;

export function disappearLabel(seconds: number | null | undefined, t: TFunction): string {
  switch (seconds) {
    case 3600: return t('messages.disappear.hour');
    case 86400: return t('messages.disappear.day');
    case 604800: return t('messages.disappear.week');
    case 2592000: return t('messages.disappear.month');
    default: return t('messages.disappear.off');
  }
}
