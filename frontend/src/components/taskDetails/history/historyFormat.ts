import type { TFunction } from 'i18next';

export const PAGE_SIZE = 4;
const MAX_VISIBLE_PAGES = 5;

const MINUTE_MS = 60 * 1000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

export type PageMarker = number | 'start-ellipsis' | 'end-ellipsis';

export function formatDuration(ms: number, t: TFunction, language: string | undefined): string {
  if (ms < MINUTE_MS) return t('taskDetails.underAMinute');
  const days = Math.floor(ms / DAY_MS);
  const hours = Math.floor((ms % DAY_MS) / HOUR_MS);
  const minutes = Math.floor((ms % HOUR_MS) / MINUTE_MS);
  const parts: [number, string][] = days > 0 ? [[days, 'day'], [hours, 'hour']] : [[hours, 'hour'], [minutes, 'minute']];
  const units = parts
    .filter(([value]) => value > 0)
    .map(([value, unit]) => new Intl.NumberFormat(language, { style: 'unit', unit, unitDisplay: 'narrow' }).format(value));
  return new Intl.ListFormat(language, { style: 'narrow', type: 'unit' }).format(units);
}

export function visiblePages(totalPages: number, current: number): PageMarker[] {
  if (totalPages <= MAX_VISIBLE_PAGES) {
    return Array.from({ length: totalPages }, (_, i) => i);
  }

  let start = Math.max(0, current - 2);
  const end = Math.min(totalPages - 1, start + MAX_VISIBLE_PAGES - 1);
  if (end - start < MAX_VISIBLE_PAGES - 1) {
    start = Math.max(0, end - MAX_VISIBLE_PAGES + 1);
  }

  const pages: PageMarker[] = [];
  if (start > 0) {
    pages.push(0);
    if (start > 1) pages.push('start-ellipsis');
  }
  for (let i = start; i <= end; i++) {
    pages.push(i);
  }
  if (end < totalPages - 1) {
    if (end < totalPages - 2) pages.push('end-ellipsis');
    pages.push(totalPages - 1);
  }
  return pages;
}
