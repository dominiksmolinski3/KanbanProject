import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { TaskActivity } from '../../types/api';
import Icon from '../Icon';
import { ICONS, MARK, SEGMENT, formatTime } from './activityText';

export default function ActivityEntry({ entry }: { entry: TaskActivity }) {
  const { t, i18n } = useTranslation();

  const values: Record<string, string> = {
    who: entry.actorName || t('activity.someone'),
    what: entry.taskTitle || t('activity.untitledTask'),
    detail: entry.detail || '',
  };
  const sentence = t(`activity.types.${entry.type}`, {
    who: `${MARK}who${MARK}`,
    what: `${MARK}what${MARK}`,
    detail: `${MARK}detail${MARK}`,
  });
  const description: ReactNode[] = sentence.split(SEGMENT).map((part, index) => {
    if (index % 2 === 0) return part;
    return <span key={index} className={`activity-seg-${part}`}>{values[part]}</span>;
  });

  return (
    <li className={`activity-entry activity-${entry.type.toLowerCase()}`}>
      <span className="activity-icon" aria-hidden="true">
        <Icon name={ICONS[entry.type] || 'activity'} size="sm" />
      </span>
      <p className="activity-what">{description}</p>
      <time className="activity-when" dateTime={entry.occurredAt}>
        {formatTime(entry.occurredAt, i18n?.language)}
      </time>
    </li>
  );
}
