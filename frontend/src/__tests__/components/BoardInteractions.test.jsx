import React from 'react';
import { render, screen, fireEvent, createEvent, within } from '@testing-library/react';
import '@testing-library/jest-dom';
import KanbanContext from '../../context/KanbanContext';
import Board from '../../components/Board';
import { toast } from 'react-toastify';

jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), success: jest.fn(), info: jest.fn(), warning: jest.fn(), dismiss: jest.fn() },
}));
jest.mock('../../components/Task', () => ({ task }) => <div data-testid={`task-${task.id}`} className="task">{task.title}</div>);
jest.mock('../../components/EditableText', () => ({ id, text, type }) => <div data-testid={`editable-${type}-${id}`}>{text}</div>);
jest.mock('../../components/TaskSearch', () => () => <div>TaskSearch</div>);
jest.mock('../../components/BoardActions', () => () => <div>BoardActions</div>);
jest.mock('../../components/AddTaskForm', () => (props) => (
  <div data-testid="add-task-form" data-column={props.defaultColumnId} data-row={props.defaultRowId}>
    <button onClick={props.onClose}>close task form</button>
  </div>
));
jest.mock('../../components/AddRowColumnForm', () => (props) => (
  <div data-testid="add-row-column-form" data-tab={props.defaultTab}>
    <button onClick={props.onClose}>close row column form</button>
  </div>
));

const columns = [
  { id: 'col1', name: 'To Do', position: 0, wipLimit: 3 },
  { id: 'col2', name: 'In Progress', position: 1, wipLimit: 0 },
];
const rows = [
  { id: 'row1', name: 'Features', position: 0, wipLimit: 0 },
  { id: 'row2', name: 'Bugs', position: 1, wipLimit: 0 },
];
const tasks = [
  { id: '1', title: 'Task 1', columnId: 'col1', rowId: 'row1' },
  { id: '2', title: 'Task 2', columnId: 'col1', rowId: 'row2' },
  { id: '3', title: 'Task 3', columnId: 'col2', rowId: 'row1' },
];

const context = (overrides = {}) => ({
  columns, rows, tasks,
  loading: false, error: null, readOnly: false, dailyFocusOnly: false, setDailyFocusOnly: jest.fn(),
  activeBoardId: 1,
  deleteRow: jest.fn(() => Promise.resolve()),
  deleteColumn: jest.fn(() => Promise.resolve()),
  updateColumnName: jest.fn(),
  updateRowName: jest.fn(),
  dragAndDrop: {
    handleDragStart: jest.fn(), handleDragOver: jest.fn(), handleDrop: jest.fn(),
    handleDragEnd: jest.fn(), handleTaskReorder: jest.fn(),
  },
  keyboardMove: { isHeld: () => false, isTarget: () => false, held: null, announcement: null },
  ...overrides,
});

const renderBoard = (value = context()) => {
  const view = render(
    <KanbanContext.Provider value={value}>
      <Board />
    </KanbanContext.Provider>
  );
  return { value, ...view };
};

const dragLeave = (node, relatedTarget) => {
  const event = createEvent.dragLeave(node);
  Object.defineProperty(event, 'relatedTarget', { value: relatedTarget });
  fireEvent(node, event);
};

const narrowScreen = (matches) => {
  window.matchMedia = jest.fn().mockReturnValue({ matches, addEventListener: jest.fn(), removeEventListener: jest.fn() });
};

describe('Board interactions', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    narrowScreen(false);
  });

  describe('on a phone', () => {
    test('shows one tab per column with its count, and the first column is the one in view', () => {
      narrowScreen(true);
      const { container } = renderBoard();

      const tabs = within(screen.getByRole('tablist')).getAllByRole('tab');
      expect(tabs).toHaveLength(2);
      expect(tabs[0]).toHaveTextContent('To Do2/3');
      expect(tabs[1]).toHaveTextContent('In Progress1');
      expect(tabs[0]).toHaveAttribute('aria-selected', 'true');
      expect(container.querySelectorAll('.grid-cell.phone-active')).toHaveLength(2);
      expect(container.querySelector('.grid-cell.phone-active')).toHaveAttribute('data-column-id', 'col1');
    });

    test('choosing another tab brings that column into view', () => {
      narrowScreen(true);
      const { container } = renderBoard();

      fireEvent.click(screen.getAllByRole('tab')[1]);

      expect(screen.getAllByRole('tab')[1]).toHaveAttribute('aria-selected', 'true');
      expect(screen.getAllByRole('tab')[0]).toHaveAttribute('aria-selected', 'false');
      expect(container.querySelector('.grid-cell.phone-active')).toHaveAttribute('data-column-id', 'col2');
    });

    test('a wide screen has no tabs', () => {
      renderBoard();

      expect(screen.queryByRole('tablist')).not.toBeInTheDocument();
    });
  });

  describe('moving a card from the keyboard', () => {
    const held = { taskId: '1', title: 'Task 1', from: { columnId: 'col1', rowId: 'row1' }, cell: { columnId: 'col1', rowId: 'row1' } };

    test('shows the strip with what is happening and the keys, while a card is held', () => {
      const { container } = renderBoard(context({
        keyboardMove: { isHeld: () => true, isTarget: () => false, held, announcement: { key: 'board.keyboardMove.over', values: {} } },
      }));

      const strip = container.querySelector('.keyboard-move-strip');
      expect(strip).toHaveTextContent('board.keyboardMove.over');
      expect(strip).toHaveTextContent('board.keyboardMove.keySpace');
      expect(strip).toHaveTextContent('Esc');
    });

    test('has no strip when nothing is held', () => {
      const { container } = renderBoard();

      expect(container.querySelector('.keyboard-move-strip')).not.toBeInTheDocument();
    });

    test('names the column and the row the held card is over', () => {
      const over = { ...held, cell: { columnId: 'col2', rowId: 'row2' } };
      const { container } = renderBoard(context({
        keyboardMove: { isHeld: () => true, isTarget: (c, r) => c === 'col2' && r === 'row2', held: over, announcement: null },
      }));

      expect(container.querySelector('.grid-column-header.keyboard-target-name')).toHaveAttribute('data-column-id', 'col2');
      expect(container.querySelector('.grid-row-header.keyboard-target-name')).toHaveAttribute('data-row-id', 'row2');
      expect(container.querySelectorAll('.keyboard-target-name')).toHaveLength(2);
    });
  });

  describe('deleting a lane', () => {
    const confirmation = () => render(toast.info.mock.calls[0][0]);

    test('a row is deleted only after the toast is confirmed', () => {
      const { value } = renderBoard();

      fireEvent.click(screen.getAllByLabelText('row.delete')[0]);
      expect(value.deleteRow).not.toHaveBeenCalled();
      expect(toast.info.mock.calls[0][1]).toMatchObject({ toastId: 'delete-row-row1', autoClose: false });

      fireEvent.click(within(confirmation().container).getByText('taskActions.yes'));

      expect(value.deleteRow).toHaveBeenCalledWith('row1');
      expect(toast.dismiss).toHaveBeenCalledWith('delete-row-row1');
    });

    test('saying no dismisses the toast and deletes nothing', () => {
      const { value } = renderBoard();

      fireEvent.click(screen.getAllByLabelText('column.delete')[1]);
      fireEvent.click(within(confirmation().container).getByText('taskActions.no'));

      expect(value.deleteColumn).not.toHaveBeenCalled();
      expect(toast.dismiss).toHaveBeenCalledWith('delete-column-col2');
    });

    test('a column is deleted after confirming', () => {
      const { value } = renderBoard();

      fireEvent.click(screen.getAllByLabelText('column.delete')[0]);
      fireEvent.click(within(confirmation().container).getByText('taskActions.yes'));

      expect(value.deleteColumn).toHaveBeenCalledWith('col1');
    });

    test('the last row cannot be deleted, and nothing is asked', () => {
      const { value } = renderBoard(context({ rows: [rows[0]] }));

      fireEvent.click(screen.getByLabelText('row.delete'));

      expect(toast.error).toHaveBeenCalledWith('row.cannotDeleteLast');
      expect(toast.info).not.toHaveBeenCalled();
      expect(value.deleteRow).not.toHaveBeenCalled();
    });
  });

  describe('adding', () => {
    test('the column and row buttons open the form on the matching tab, and closing it removes it', () => {
      renderBoard();

      fireEvent.click(screen.getByTitle('column.add'));
      expect(screen.getByTestId('add-row-column-form')).toHaveAttribute('data-tab', 'column');
      fireEvent.click(screen.getByText('close row column form'));
      expect(screen.queryByTestId('add-row-column-form')).not.toBeInTheDocument();

      fireEvent.click(screen.getByTitle('row.add'));
      expect(screen.getByTestId('add-row-column-form')).toHaveAttribute('data-tab', 'row');
    });

    test('a cell\'s add button opens the task form with that cell\'s column and row', () => {
      renderBoard();

      fireEvent.click(screen.getAllByTitle('taskActions.addTaskHere')[0]);

      expect(screen.getByTestId('add-task-form')).toHaveAttribute('data-column', 'col1');
      expect(screen.getByTestId('add-task-form')).toHaveAttribute('data-row', 'row1');
      fireEvent.click(screen.getByText('close task form'));
      expect(screen.queryByTestId('add-task-form')).not.toBeInTheDocument();
    });
  });

  describe('on a read-only board', () => {
    test('offers no way to delete, add or drag', () => {
      const { container } = renderBoard(context({ readOnly: true }));

      expect(screen.queryByLabelText('row.delete')).not.toBeInTheDocument();
      expect(screen.queryByLabelText('column.delete')).not.toBeInTheDocument();
      expect(screen.queryByTitle('column.add')).not.toBeInTheDocument();
      expect(screen.queryByTitle('row.add')).not.toBeInTheDocument();
      expect(screen.queryByTitle('taskActions.addTaskHere')).not.toBeInTheDocument();
      expect(container.querySelector('.grid-column-header[draggable="true"]')).not.toBeInTheDocument();
      expect(screen.getByText('board.readOnlyBanner')).toBeInTheDocument();
    });
  });

  describe('dragging lanes', () => {
    test('picking up a column or a row names its kind', () => {
      const { value, container } = renderBoard();

      fireEvent.dragStart(container.querySelector('.grid-column-header[data-column-id="col2"]'));
      fireEvent.dragStart(container.querySelector('.grid-row-header[data-row-id="row2"]'));

      expect(value.dragAndDrop.handleDragStart).toHaveBeenCalledWith(expect.anything(), 'col2', 'column');
      expect(value.dragAndDrop.handleDragStart).toHaveBeenCalledWith(expect.anything(), 'row2', 'row');
    });

    test('a cell lights up while something is over it, goes dark when it leaves, and again on a drop', () => {
      const { value, container } = renderBoard();
      const cell = container.querySelector('.grid-cell[data-column-id="col1"][data-row-id="row1"]');

      fireEvent.dragEnter(cell);
      expect(cell).toHaveClass('drop-target');

      dragLeave(cell, document.body);
      expect(cell).not.toHaveClass('drop-target');

      fireEvent.dragEnter(cell);
      fireEvent.drop(cell);
      expect(cell).not.toHaveClass('drop-target');
      expect(value.dragAndDrop.handleDrop).toHaveBeenCalledWith(expect.anything(), 'col1', 'row1');
    });

    test('moving between a cell and something inside it does not turn it off', () => {
      const { container } = renderBoard();
      const cell = container.querySelector('.grid-cell[data-column-id="col1"][data-row-id="row1"]');

      fireEvent.dragEnter(cell);
      dragLeave(cell, cell.querySelector('.task'));

      expect(cell).toHaveClass('drop-target');
    });

    test('the end of any drag clears every highlight on the page', () => {
      const { container } = renderBoard();
      const cell = container.querySelector('.grid-cell[data-column-id="col1"][data-row-id="row1"]');
      const header = container.querySelector('.grid-column-header[data-column-id="col2"]');

      fireEvent.dragEnter(cell);
      fireEvent.dragEnter(header);
      fireEvent.dragEnd(document);

      expect(container.querySelectorAll('.drop-target')).toHaveLength(0);
    });

    test('dropping a column over the board lets it through', () => {
      const { container } = renderBoard();

      const allowed = fireEvent.dragOver(container.querySelector('.board-grid'), { dataTransfer: { types: ['application/column'] } });

      expect(allowed).toBe(false);
    });
  });
});
