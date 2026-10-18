import { useTranslation } from 'react-i18next';
import { useKanban } from '../../context/KanbanContext';
import { splitPriority } from '../../board/cardModel';
import type { Task } from '../../types/api';
import Icon from '../Icon';
import TaskLabels from '../TaskLabels';
import TaskAssignees from './TaskAssignees';
import TaskDeadline from './TaskDeadline';
import TaskRelations from './TaskRelations';
import type { TaskDetailsState } from './useTaskDetails';

export default function TaskDetailsProps({ task, state }: { task: Task; state: TaskDetailsState }) {
  const { t } = useTranslation();
  const { readOnly } = useKanban();
  const { priority } = splitPriority(state.labels);

  return (
    <dl className="sheet-props">
      <div className="sheet-prop">
        <dt><Icon name="people" size="sm" />{t('taskDetails.props.assignees')}</dt>
        <dd>
          <TaskAssignees
            taskId={task.id}
            users={state.users}
            assignedUsers={state.assignedUsers}
            readOnly={readOnly}
            onRequestRemove={state.setUserToRemove}
            onChanged={state.reloadAfterChange}
          />
        </dd>
      </div>
      <div className="sheet-prop">
        <dt><Icon name="filter" size="sm" />{t('board.labels')}</dt>
        <dd>
          <TaskLabels
            taskId={task.id}
            initialLabels={state.labels}
            onLabelsChange={state.changeLabels}
            readOnly={readOnly}
          />
        </dd>
      </div>
      <div className="sheet-prop">
        <dt><Icon name="priority" size="sm" />{t('taskDetails.props.priority')}</dt>
        <dd>
          {priority ? (
            <span className={`task-priority-pill priority-${priority}`}>
              <span className="priority-bars" aria-hidden="true"><i /><i /><i /><i /></span>
              {t(`taskActions.priority.${priority}`)}
            </span>
          ) : (
            <span className="sheet-prop-empty" aria-hidden="true">–</span>
          )}
        </dd>
      </div>
      <div className="sheet-prop">
        <dt><Icon name="calendar" size="sm" />{t('taskActions.deadline')}</dt>
        <dd>
          <TaskDeadline deadline={task.deadline} readOnly={readOnly} onSave={state.saveDeadline} />
        </dd>
      </div>
      <div className="sheet-prop">
        <dt><Icon name="link" size="sm" />{t('taskDetails.parentTask')}</dt>
        <dd>
          <TaskRelations
            part="parent"
            taskId={task.id}
            parentTaskId={state.parentTaskId}
            readOnly={readOnly}
            onChanged={state.reloadAfterChange}
          />
        </dd>
      </div>
    </dl>
  );
}
