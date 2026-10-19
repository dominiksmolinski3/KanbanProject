import type { TFunction } from 'i18next';
import type { TaskActivity, TaskActivityType } from '../../types/api';

export const MARK = '⁣';
export const SEGMENT = new RegExp(`${MARK}(who|what|detail)${MARK}`);

export const ICONS: Record<TaskActivityType, string> = {
  CREATED: 'plus',
  MOVED: 'arrow-right',
  ASSIGNED: 'user',
  UNASSIGNED: 'user',
  COMPLETED: 'check',
  REOPENED: 'reopen',
  DELETED: 'trash',
  COMMENTED: 'comment',
};

export interface ActivityDay {
  key: string;
  label: string;
  entries: TaskActivity[];
}

const dayKey = (date: Date) => `${date.getFullYear()}-${date.getMonth()}-${date.getDate()}`;

function dayLabel(date: Date, language: string | undefined, t: TFunction): string {
  const today = new Date();
  const yesterday = new Date(today);
  yesterday.setDate(today.getDate() - 1);
  if (dayKey(date) === dayKey(today)) return t('activity.today');
  if (dayKey(date) === dayKey(yesterday)) return t('activity.yesterday');
  try {
    return new Intl.DateTimeFormat(language || undefined, { weekday: 'long', day: 'numeric', month: 'long' }).format(date);
  } catch {
    return date.toLocaleDateString();
  }
}

export function groupByDay(entries: TaskActivity[], language: string | undefined, t: TFunction): ActivityDay[] {
  const days: ActivityDay[] = [];
  for (const entry of entries) {
    const date = new Date(entry.occurredAt);
    const key = dayKey(date);
    let day = days[days.length - 1];
    if (!day || day.key !== key) {
      day = { key, label: dayLabel(date, language, t), entries: [] };
      days.push(day);
    }
    day.entries.push(entry);
  }
  return days;
}

export function formatTime(value: string, language: string | undefined): string {
  try {
    return new Intl.DateTimeFormat(language || undefined, { hour: '2-digit', minute: '2-digit' }).format(new Date(value));
  } catch {
    return new Date(value).toLocaleTimeString();
  }
}
