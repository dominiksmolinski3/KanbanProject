import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import Icon from './Icon';
import AddTaskForm from './AddTaskForm';
import AddRowColumnForm from './AddRowColumnForm';
import WipLimitControl from './WipLimitControl';

function BoardActions() {
  const [activeForm, setActiveForm] = useState(null);
  const { t } = useTranslation();

  const toggle = (form) => setActiveForm(activeForm === form ? null : form);
  const close = () => setActiveForm(null);

  return (
    <>
      <div className="board-actions" role="group" aria-label={t('board.actions')}>
        <button
          type="button"
          className="board-action primary"
          onClick={() => toggle('task')}
          data-testid="open-add-task-form"
        >
          <Icon name="plus" size="sm" />
          {t('header.addTask')}
        </button>
        <button
          type="button"
          className="board-action"
          onClick={() => toggle('boardItem')}
          data-testid="open-add-board-item-form"
        >
          {t('header.addBoardItem')}
        </button>
        <button
          type="button"
          className="board-action"
          onClick={() => toggle('wip')}
          data-testid="open-wip-limit-form"
        >
          {t('header.wipLimit')}
        </button>
      </div>

      {activeForm === 'task' && <AddTaskForm onClose={close} />}
      {activeForm === 'wip' && <WipLimitControl onClose={close} />}
      {activeForm === 'boardItem' && <AddRowColumnForm onClose={close} />}
    </>
  );
}

export default BoardActions;
