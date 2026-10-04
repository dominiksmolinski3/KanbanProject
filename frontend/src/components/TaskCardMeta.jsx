import React, { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { hueOf, readStoredLabelColors, splitPriority } from '../board/cardModel';
import Icon from './Icon';

const MAX_LABELS = 3;

function formatDue(deadline, language) {
  try {
    return new Intl.DateTimeFormat(language || undefined, { day: 'numeric', month: 'short' })
      .format(new Date(deadline));
  } catch {
    return String(deadline);
  }
}

function TaskCardMeta({ task, dueState, children }) {
  const { t, i18n } = useTranslation();
  const { priority, priorityLabel, labels } = useMemo(() => splitPriority(task.labels || []), [task.labels]);
  const storedColors = labels.length > 0 ? readStoredLabelColors() : {};
  const openSubtasks = task.openSubtasks ?? 0;
  const totalSubtasks = Math.max(task.subtaskCount ?? 0, openSubtasks);
  const doneSubtasks = totalSubtasks - openSubtasks;
  const visibleLabels = labels.slice(0, MAX_LABELS);
  const hiddenLabels = labels.length - visibleLabels.length;

  const hasAssignees = (task.userIds || []).length > 0;
  const childCount = (task.childTaskIds || []).length;
  const hasParent = task.parentTaskId !== null && task.parentTaskId !== undefined;
  const hasRelations = childCount > 0 || hasParent;
  if (!priority && labels.length === 0 && !dueState && totalSubtasks === 0 && !hasAssignees && !hasRelations) {
    return null;
  }

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
              <span className="priority-bars" aria-hidden="true"><i /><i /><i /><i /></span>
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

      {(dueState || totalSubtasks > 0 || hasAssignees || hasRelations) && (
        <div className="task-signals">
          {dueState && (
            <span className={`due-chip due-${dueState}`} title={t(dueKey, { date: due })}>
              <Icon name="calendar" size="sm" />
              <span className="visually-hidden">{t(dueKey, { date: due })}</span>
              <span aria-hidden="true">{due}</span>
            </span>
          )}
          {totalSubtasks > 0 && (
            <span
              className={`subtask-chip${openSubtasks === 0 ? ' subtask-done' : ''}`}
              title={t('taskActions.subtaskProgress', { done: doneSubtasks, total: totalSubtasks })}
            >
              <Icon name="checklist" size="sm" />
              <span className="visually-hidden">
                {t('taskActions.subtaskProgress', { done: doneSubtasks, total: totalSubtasks })}
              </span>
              <span aria-hidden="true">{doneSubtasks}/{totalSubtasks}</span>
              <span className="subtask-bar" aria-hidden="true">
                <span style={{ width: `${Math.round((doneSubtasks / totalSubtasks) * 100)}%` }} />
              </span>
            </span>
          )}
          {childCount > 0 && (
            <span className="relation-chip" title={t('taskActions.childCount', { count: childCount })}>
              <Icon name="link" size="sm" />
              <span className="visually-hidden">{t('taskActions.childCount', { count: childCount })}</span>
              <span aria-hidden="true">{childCount}</span>
            </span>
          )}
          {hasParent && (
            <span className="relation-chip" title={t('taskActions.hasParent')}>
              <Icon name="link" size="sm" />
              <span className="visually-hidden">{t('taskActions.hasParent')}</span>
            </span>
          )}
          {children}
        </div>
      )}
    </div>
  );
}

export default TaskCardMeta;
