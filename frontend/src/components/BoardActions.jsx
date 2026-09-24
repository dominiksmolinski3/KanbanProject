import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import AddTaskForm from './AddTaskForm';
import AddRowColumnForm from './AddRowColumnForm';
import WipLimitControl from './WipLimitControl';

const ICON_PROPS = {
  xmlns: 'http://www.w3.org/2000/svg',
  fill: 'none',
  viewBox: '0 0 24 24',
  stroke: 'currentColor',
  width: 18,
  height: 18,
  'aria-hidden': true,
};

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
          <svg {...ICON_PROPS}>
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 4v16m8-8H4" />
          </svg>
          {t('header.addTask')}
        </button>
        <button
          type="button"
          className="board-action"
          onClick={() => toggle('boardItem')}
          data-testid="open-add-board-item-form"
        >
          <svg {...ICON_PROPS}>
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16m-7 6h7" />
          </svg>
          {t('header.addBoardItem')}
        </button>
        <button
          type="button"
          className="board-action"
          onClick={() => toggle('wip')}
          data-testid="open-wip-limit-form"
        >
          <svg {...ICON_PROPS}>
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h10M4 18h6" />
          </svg>
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
