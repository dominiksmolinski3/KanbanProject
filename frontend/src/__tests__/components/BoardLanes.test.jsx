import React from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import KanbanContext from '../../context/KanbanContext';
import Board from '../../components/Board';

jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), success: jest.fn(), info: jest.fn(), warning: jest.fn(), dismiss: jest.fn() }
}));

jest.mock('../../components/Task', () => function MockTask({ task }) {
  return <div data-testid={`task-${task.id}`} className="task">{task.title}</div>;
});

jest.mock('../../components/TaskSearch', () => function MockTaskSearch() {
  return null;
});

jest.mock('../../components/EditableText', () => function MockEditableText({ id, text, type }) {
  return <div data-testid={`editable-${type}-${id}`} className="editable-text">{text}</div>;
});

const keyboardMove = {
  isHeld: () => false,
  isTarget: () => false,
  grab: jest.fn(),
  step: jest.fn(),
  drop: jest.fn(),
  cancel: jest.fn(),
  announcement: null,
};

const dragAndDrop = {
  handleDragStart: jest.fn(),
  handleDragOver: jest.fn(),
  handleDrop: jest.fn(),
  handleTaskReorder: jest.fn(),
};

function renderBoard(overrides = {}) {
  const value = {
    activeBoardId: 7,
    columns: [
      { id: 'c1', name: 'To Do', wipLimit: 5 },
      { id: 'c2', name: 'Doing', wipLimit: 2 },
    ],
    rows: [
      { id: 'r1', name: 'Features', wipLimit: 0 },
      { id: 'r2', name: 'Bugs', wipLimit: 0 },
    ],
    tasks: [
      { id: 1, title: 'One', columnId: 'c1', rowId: 'r1' },
      { id: 2, title: 'Two', columnId: 'c1', rowId: 'r1' },
      { id: 3, title: 'Three', columnId: 'c2', rowId: 'r2' },
      { id: 4, title: 'Four', columnId: 'c2', rowId: 'r2' },
    ],
    loading: false,
    error: null,
    deleteRow: jest.fn(),
    deleteColumn: jest.fn(),
    updateColumnName: jest.fn(),
    updateRowName: jest.fn(),
    dailyFocusOnly: false,
    setDailyFocusOnly: jest.fn(),
    dragAndDrop,
    keyboardMove,
    readOnly: false,
    ...overrides,
  };
  return render(
    <KanbanContext.Provider value={value}>
      <Board />
    </KanbanContext.Provider>
  );
}

describe('swimlanes', () => {
  beforeEach(() => localStorage.clear());

  test('collapsing a lane hides its cards behind a count and is remembered per board', () => {
    const { unmount } = renderBoard();

    const [featuresToggle] = screen.getAllByRole('button', { name: 'board.lane.collapse' });
    expect(featuresToggle).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('task-1')).toBeInTheDocument();

    fireEvent.click(featuresToggle);

    expect(screen.queryByTestId('task-1')).not.toBeInTheDocument();
    expect(screen.getByTestId('task-3')).toBeInTheDocument();
    expect(screen.getByText('board.lane.hidden')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'board.lane.expand' })).toHaveAttribute('aria-expanded', 'false');
    expect(JSON.parse(localStorage.getItem('kanban.collapsedLanes.7'))).toEqual(['r1']);

    unmount();
    renderBoard();
    expect(screen.queryByTestId('task-1')).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: 'board.lane.expand' }));
    expect(screen.getByTestId('task-1')).toBeInTheDocument();
  });

  test('another board starts with every lane open', () => {
    localStorage.setItem('kanban.collapsedLanes.7', JSON.stringify(['r1']));
    renderBoard({ activeBoardId: 8 });
    expect(screen.getByTestId('task-1')).toBeInTheDocument();
  });

  test('unreadable stored state leaves the lanes open instead of breaking the board', () => {
    localStorage.setItem('kanban.collapsedLanes.7', '{oops');
    renderBoard();
    expect(screen.getByTestId('task-1')).toBeInTheDocument();
  });

  test('a collapsed lane still accepts a drop', () => {
    localStorage.setItem('kanban.collapsedLanes.7', JSON.stringify(['r1']));
    const { container } = renderBoard();
    const cell = container.querySelector('.grid-cell[data-column-id="c2"][data-row-id="r1"]');
    fireEvent.drop(cell, { dataTransfer: { types: ['application/task'], getData: () => '' } });
    expect(dragAndDrop.handleDrop).toHaveBeenCalledWith(expect.anything(), 'c2', 'r1');
  });
});

describe('WIP signals in the column header', () => {
  test('a column at its limit is marked as approaching it, one over it as exceeded', () => {
    renderBoard({
      tasks: [
        { id: 1, title: 'One', columnId: 'c2', rowId: 'r1' },
        { id: 2, title: 'Two', columnId: 'c2', rowId: 'r2' },
        { id: 3, title: 'Three', columnId: 'c1', rowId: 'r1' },
      ],
    });

    const doing = screen.getByTestId('editable-column-c2').closest('th');
    expect(doing).toHaveClass('wip-near');
    expect(doing).not.toHaveClass('wip-exceeded');
    expect(doing.querySelector('.wip-limit')).toHaveClass('near');
    expect(doing.style.getPropertyValue('--wip-fill')).toBe('100%');
    expect(doing.querySelector('.wip-bar')).toBeInTheDocument();

    const todo = screen.getByTestId('editable-column-c1').closest('th');
    expect(todo).toHaveClass('wip-ok');
    expect(todo.style.getPropertyValue('--wip-fill')).toBe('20%');
  });

  test('a drag over a cell lights it up until the drag leaves it', () => {
    const { container } = renderBoard();
    const cell = container.querySelector('.grid-cell[data-column-id="c1"][data-row-id="r2"]');

    fireEvent.dragEnter(cell, { dataTransfer: { types: ['application/task'] } });
    expect(cell).toHaveClass('drop-target');

    fireEvent.dragLeave(cell, { relatedTarget: document.body, dataTransfer: { types: ['application/task'] } });
    expect(cell).not.toHaveClass('drop-target');

    fireEvent.dragEnter(cell, { dataTransfer: { types: ['application/task'] } });
    fireEvent.dragEnd(document);
    expect(cell).not.toHaveClass('drop-target');
  });
});
