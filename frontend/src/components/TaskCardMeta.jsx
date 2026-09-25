import React, { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { hueOf, readStoredLabelColors, splitPriority } from '../board/cardModel';

const MAX_LABELS = 3;

function formatDue(deadline, language) {
  try {
    return new Intl.DateTimeFormat(language || undefined, { day: 'numeric', month: 'short' })
      .format(new Date(deadline));
  } catch {
    return String(deadline);
  }
}

function TaskCardMeta({ task, dueState }) {
  const { t, i18n } = useTranslation();
  const { priority, priorityLabel, labels } = useMemo(() => splitPriority(task.labels || []), [task.labels]);
  const storedColors = labels.length > 0 ? readStoredLabelColors() : {};
  const openSubtasks = task.openSubtasks ?? 0;
  const visibleLabels = labels.slice(0, MAX_LABELS);
  const hiddenLabels = labels.length - visibleLabels.length;

  if (!priority && labels.length === 0 && !dueState && openSubtasks === 0) return null;

  const due = dueState ? formatDue(task.deadline, i18n?.language) : null;
  const dueKey = { overdue: 'taskActions.dueOverdue', soon: 'taskActions.dueSoon', later: 'taskActions.dueLater' }[dueState];

  return (
    <div className="task-meta">
      {(priority || labels.length > 0) && (
        <div className="task-labels-preview">
          {priority && (
            <span
              className={`task-priority-pill priority-${priority}`}
              title={t('taskActions.priorityTitle', { level: t(`taskActions.priority.${priority}`) })}
              data-label={priorityLabel}
            >
              <span className="priority-glyph" aria-hidden="true" />
              {t(`taskActions.priority.${priority}`)}
            </span>
          )}
          {visibleLabels.map((label) => (
            <span
              key={label}
              className="task-label-pill"
              title={label}
              style={storedColors[label]
                ? { '--label-color': storedColors[label] }
                : { '--label-hue': hueOf(label) }}
            >
              <span className="label-dot" aria-hidden="true" />
              <span className="task-label-text">{label}</span>
            </span>
          ))}
          {hiddenLabels > 0 && (
            <span className="task-label-count" title={t('taskActions.moreLabels', { n: hiddenLabels })}>
              +{hiddenLabels}
            </span>
          )}
        </div>
      )}

      {(dueState || openSubtasks > 0) && (
        <div className="task-signals">
          {dueState && (
            <span className={`due-chip due-${dueState}`} title={t(dueKey, { date: due })}>
              <span className="due-icon" aria-hidden="true" />
              <span className="visually-hidden">{t(dueKey, { date: due })}</span>
              <span aria-hidden="true">{due}</span>
            </span>
          )}
          {openSubtasks > 0 && (
            <span className="subtask-chip" title={t('taskActions.subtasksOpen', { n: openSubtasks })}>
              <span className="subtask-icon" aria-hidden="true" />
              <span className="visually-hidden">{t('taskActions.subtasksOpen', { n: openSubtasks })}</span>
              <span aria-hidden="true">{openSubtasks}</span>
            </span>
          )}
        </div>
      )}
    </div>
  );
}

export default TaskCardMeta;
