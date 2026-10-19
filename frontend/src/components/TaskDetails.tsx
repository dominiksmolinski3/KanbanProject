import { useState } from 'react';
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
import ChildTasks from './taskDetails/relations/ChildTasks';
import TaskSubtasks from './taskDetails/TaskSubtasks';
import { useTaskDetails } from './taskDetails/useTaskDetails';
import Modal from './ui/Modal';
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

  const title = state.title.value || task.title;
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
        <ChildTasks taskId={task.id} />
      </>
    );
  };

  return (
    <Modal
      onClose={onClose}
      overlayClassName="task-details-overlay"
      contentClassName={`task-details-panel${state.loading ? ' loading' : ''}`}
      label={title}
      busy={state.loading}
    >
      {state.loading ? (
        <p>{t('board.loading')}</p>
      ) : (
        <>
          <div className="panel-header">
            {(columnName || rowName) && (
              <div className="sheet-crumbs">
                {columnName && <span>{columnName}</span>}
                {columnName && rowName && <Icon name="chevron-right" size="sm" />}
                {rowName && <span>{rowName}</span>}
              </div>
            )}
            <TaskDetailsTitle task={task} state={state} title={title} onClose={onClose} />
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
        </>
      )}
    </Modal>
  );
}

export default TaskDetails;
