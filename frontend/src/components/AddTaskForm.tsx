import { useState, type FormEvent } from 'react';
import LengthHint from './LengthHint';
import { NAME_MAX_LENGTH } from '../services/textLimits';
import { useKanban } from '../context/KanbanContext';
import { useTranslation } from 'react-i18next';
import Icon from './Icon';
import '../styles/components/Forms.css';
import FormModal from './FormModal';
import { useAsyncAction } from '../hooks/useAsyncAction';
import type { LooseId } from '../context/kanban/types';
import type { Id } from '../types/api';

interface AddTaskFormProps {
  onClose: () => void;
  defaultColumnId?: LooseId;
  defaultRowId?: LooseId;
}

function AddTaskForm({ onClose, defaultColumnId = '', defaultRowId = '' }: AddTaskFormProps) {
  const { addTask, refreshTasks, columns, rows } = useKanban();
  const [title, setTitle] = useState('');
  const [deadline, setDeadline] = useState('');
  const [selectedColumnId, setSelectedColumnId] = useState<LooseId>(defaultColumnId || '');
  const [selectedRowId, setSelectedRowId] = useState<LooseId>(defaultRowId || '');
  const { pending: isSubmitting, error, setError, run } = useAsyncAction();
  const { t } = useTranslation();

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();

    if (!title.trim()) {
      setError(t('forms.addTaskForm.titleRequired'));
      return;
    }

    const added = await run(async () => {
      // The selects hand back strings, and the board has always sent them on as they are.
      await addTask(title, (selectedColumnId || null) as Id | null, deadline || null, (selectedRowId || null) as Id | null);
      await refreshTasks();
    }, t('forms.addTaskForm.addError'));

    if (added) {
      setTitle('');
      setDeadline('');
      setSelectedColumnId('');
      setSelectedRowId('');
      if (onClose) onClose();
    }
  };

  return (
    <FormModal onClose={onClose} ariaLabel={t('forms.addTaskForm.title')}>
    <div className="form-container">
      <form onSubmit={handleSubmit}>
        <div className="form-header">
          <h3>{t('forms.addTaskForm.title')}</h3>
          <button
            type="button"
            className="close-button"
            onClick={onClose}
            aria-label={t('forms.addRowColumn.close')}
          >
            <Icon name="close" />
          </button>
        </div>

        {error && <div className="error-message">{error}</div>}

        <div className="form-group">
          <label htmlFor="task-title">{t('forms.addTaskForm.titleLabel')}</label>
          <input
            id="task-title"
            type="text"
            value={title}
            maxLength={NAME_MAX_LENGTH}
            onChange={(e) => setTitle(e.target.value)}
            placeholder={t('forms.addTaskForm.titlePlaceholder')}
            disabled={isSubmitting}
          />
          <LengthHint value={title} max={NAME_MAX_LENGTH} />
        </div>

        <div className="form-group">
          <label htmlFor="task-column">{t('forms.addTaskForm.columnLabel')}</label>
          <select
            id="task-column"
            value={selectedColumnId}
            onChange={(e) => setSelectedColumnId(e.target.value)}
            disabled={isSubmitting}
          >
            <option value="">{t('forms.addTaskForm.selectColumn')}</option>
            {columns && columns.map(column => (
              <option key={column.id} value={column.id}>
                {column.name}
              </option>
            ))}
          </select>
          <small className="help-text">{t('forms.addTaskForm.columnHelp', 'Optional. Tasks without a column will appear in the backlog.')}</small>
        </div>

        {rows && rows.length > 0 && (
          <div className="form-group">
            <label htmlFor="task-row">{t('forms.addTaskForm.rowLabel', 'Row')}</label>
            <select
              id="task-row"
              value={selectedRowId}
              onChange={(e) => setSelectedRowId(e.target.value)}
              disabled={isSubmitting}
            >
              <option value="">{t('forms.addTaskForm.selectRow', 'Select row')}</option>
              {rows.map(row => (
                <option key={row.id} value={row.id}>
                  {row.name}
                </option>
              ))}
            </select>
            <small className="help-text">{t('forms.addTaskForm.rowHelp', 'Optional. Defaults to the first row if none is selected.')}</small>
          </div>
        )}

        <div className="form-group">
          <label htmlFor="task-deadline">{t('forms.addTaskForm.deadlineLabel', 'Deadline')}</label>
          <input
            id="task-deadline"
            type="datetime-local"
            value={deadline}
            onChange={(e) => setDeadline(e.target.value)}
            disabled={isSubmitting}
            className="deadline-input"
          />
          <small className="help-text">{t('forms.addTaskForm.deadlineHelp', 'Optional. Tasks will be marked as expired when the deadline passes.')}</small>
        </div>

        <div className="form-actions">
          <button
            type="submit"
            disabled={isSubmitting}
          >
            {isSubmitting ? t('forms.addTaskForm.adding') : t('header.addTask')}
          </button>
          <button
            type="button"
            className="cancel-button"
            onClick={onClose}
            disabled={isSubmitting}
          >
            {t('taskActions.cancel')}
          </button>
        </div>
      </form>
    </div>
    </FormModal>
  );
}

export default AddTaskForm;
