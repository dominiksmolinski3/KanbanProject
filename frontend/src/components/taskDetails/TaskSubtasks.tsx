import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { Id, SubTask } from '../../types/api';
import ConfirmDialog from './ConfirmDialog';
import SubtaskAddForm from './subtasks/SubtaskAddForm';
import SubtaskItem from './subtasks/SubtaskItem';
import { useSubtasks } from './subtasks/useSubtasks';

interface TaskSubtasksProps {
  taskId: Id;
  readOnly: boolean;
  onSubtaskUpdate?: () => void;
  onSaved: () => void;
}

function TaskSubtasks({ taskId, readOnly, onSubtaskUpdate, onSaved }: TaskSubtasksProps) {
  const { t } = useTranslation();
  const { subtasks, loaded, add, toggle, remove, saveDescription } = useSubtasks(taskId, onSubtaskUpdate, onSaved);
  const [expandedId, setExpandedId] = useState<Id | null>(null);
  const [toDelete, setToDelete] = useState<SubTask | null>(null);

  const confirmDelete = async () => {
    if (!toDelete) return;
    try {
      await remove(toDelete.id);
    } finally {
      setToDelete(null);
    }
  };

  const doneCount = subtasks.filter(subtask => subtask.completed).length;

  return (
    <section className="subtasks-section">
      <div className="section-header">
        <h3>{t('taskActions.subtasks')}</h3>
        {subtasks.length > 0 && (
          <span
            className="section-meta"
            title={t('taskActions.subtaskProgress', { done: doneCount, total: subtasks.length })}
          >
            {doneCount}/{subtasks.length}
          </span>
        )}
      </div>
      {subtasks.length > 0 && (
        <div className="subtask-progress" aria-hidden="true">
          <span style={{ width: `${Math.round((doneCount / subtasks.length) * 100)}%` }} />
        </div>
      )}

      {!readOnly && <SubtaskAddForm onAdd={add} />}

      {subtasks.length > 0 ? (
        <div className="subtasks-list">
          {subtasks.map(subtask => (
            <SubtaskItem
              key={subtask.id}
              subtask={subtask}
              readOnly={readOnly}
              expanded={expandedId === subtask.id}
              onToggle={() => toggle(subtask.id)}
              onExpand={() => setExpandedId(expandedId === subtask.id ? null : subtask.id)}
              onAskDelete={() => setToDelete(subtask)}
              onSaveDescription={(description) => saveDescription(subtask.id, description)}
            />
          ))}
        </div>
      ) : loaded && (
        <p className="no-subtasks">{t('taskActions.noSubtasks')}</p>
      )}

      {toDelete && (
        <ConfirmDialog
          title={t('taskActions.confirmDeleteSubTask')}
          subject={toDelete.title}
          onConfirm={confirmDelete}
          onCancel={() => setToDelete(null)}
        />
      )}
    </section>
  );
}

export default TaskSubtasks;
