import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { useTranslation } from 'react-i18next';
import { useKanban } from '../context/KanbanContext';
import type { Task } from '../types/api';
import Icon from './Icon';
import TaskComments from './TaskComments';
import ConfirmDialog from './taskDetails/ConfirmDialog';
import TaskAttachments from './taskDetails/TaskAttachments';
import TaskColumnHistory from './taskDetails/TaskColumnHistory';
import TaskDetailsDescription from './taskDetails/TaskDetailsDescription';
import TaskDetailsProps from './taskDetails/TaskDetailsProps';
import TaskDetailsTitle from './taskDetails/TaskDetailsTitle';
import TaskRelations from './taskDetails/TaskRelations';
import TaskSubtasks from './taskDetails/TaskSubtasks';
import { useTaskDetails } from './taskDetails/useTaskDetails';
import '../styles/components/TaskDetails.css';

type View = 'details' | 'comments' | 'history';

interface TaskDetailsProps_ {
  task: Task;
  onClose: () => void;
  onSubtaskUpdate?: () => void;
}

function TaskDetails({ task, onClose, onSubtaskUpdate }: TaskDetailsProps_) {
  const { readOnly, columns = [], rows = [] } = useKanban();
  const { t } = useTranslation();
  const state = useTaskDetails(task);
  const [currentView, setCurrentView] = useState<View>('details');

  useEffect(() => {
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };
    document.addEventListener('keydown', handleEscape);
    return () => document.removeEventListener('keydown', handleEscape);
  }, [onClose]);

  if (state.loading) {
    return createPortal(
      <>
        <div className="task-details-overlay" />
        <section className="task-details-panel loading" aria-busy="true">
          <p>{t('board.loading')}</p>
        </section>
      </>,
      document.body
    );
  }

  const title = state.title.value || task.title;
  const titleId = `task-sheet-title-${task.id}`;
  const columnName = columns.find((column) => String(column.id) === String(task.columnId))?.name;
  const rowName = rows.find((row) => String(row.id) === String(task.rowId))?.name;

  const tabs: { id: View; label: string; icon?: string }[] = [
    { id: 'details', label: t('taskDetails.tabs.details') },
    { id: 'comments', label: t('taskComments.heading'), icon: 'comment' },
    { id: 'history', label: t('taskDetails.historyAndTimeline') },
  ];

  const renderView = () => {
    if (currentView === 'comments') {
      return <TaskComments taskId={task.id} />;
    }
    if (currentView === 'history') {
      return <TaskColumnHistory taskId={task.id} />;
    }
    return (
      <>
        <TaskDetailsDescription state={state} />
        <TaskSubtasks
          taskId={task.id}
          readOnly={readOnly}
          onSubtaskUpdate={onSubtaskUpdate}
          onSaved={state.flashSaved}
        />
        <TaskAttachments taskId={task.id} readOnly={readOnly} />
        <TaskRelations
          part="children"
          taskId={task.id}
          parentTaskId={state.parentTaskId}
          readOnly={readOnly}
          onChanged={state.reloadAfterChange}
        />
      </>
    );
  };

  return createPortal(
    <>
      <div
        className="task-details-overlay"
        onClick={(event) => {
          event.stopPropagation();
          onClose();
        }}
      />
      <section
        className="task-details-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
      >
        <div className="panel-header">
          {(columnName || rowName) && (
            <div className="sheet-crumbs">
              {columnName && <span>{columnName}</span>}
              {columnName && rowName && <Icon name="chevron-right" size="sm" />}
              {rowName && <span>{rowName}</span>}
            </div>
          )}
          <TaskDetailsTitle task={task} state={state} titleId={titleId} title={title} onClose={onClose} />
          <TaskDetailsProps task={task} state={state} />
          <div className="sheet-tabs" role="tablist" aria-label={title}>
            {tabs.map((tab) => (
              <button
                key={tab.id}
                type="button"
                role="tab"
                id={`sheet-tab-${tab.id}`}
                aria-selected={currentView === tab.id}
                aria-controls="sheet-body"
                className="sheet-tab"
                onClick={() => setCurrentView(tab.id)}
              >
                {tab.icon && <Icon name={tab.icon} size="sm" />}
                {tab.label}
              </button>
            ))}
          </div>
        </div>

        <div
          className="task-details-main"
          id="sheet-body"
          role="tabpanel"
          aria-labelledby={`sheet-tab-${currentView}`}
        >
          {renderView()}

          {state.userToRemove && (
            <ConfirmDialog
              title={t('taskActions.confirmDeleteAssignedUser')}
              subject={state.userToRemove.name}
              onConfirm={state.removeUser}
              onCancel={state.cancelRemoveUser}
            />
          )}
        </div>

        {state.saved && (
          <div className="success-message" role="status">
            <Icon name="check" size="sm" />
            {t('notifications.taskUpdated')}
          </div>
        )}
      </section>
    </>,
    document.body
  );
}

export default TaskDetails;
