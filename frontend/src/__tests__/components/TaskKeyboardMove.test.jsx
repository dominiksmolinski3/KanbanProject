import React from 'react';
import { render, screen, act, fireEvent } from '@testing-library/react';
import KanbanContext from '../../context/KanbanContext';
import Task from '../../components/Task';

jest.mock('../../services/api', () => ({
  getUserAvatar: jest.fn().mockResolvedValue(null),
  assignUserToTask: jest.fn().mockResolvedValue({}),
  fetchSubTasksByTaskId: jest.fn().mockResolvedValue([]),
  fetchTask: jest.fn().mockResolvedValue({}),
  getChildTasks: jest.fn().mockResolvedValue([]),
  WipLimitExceededError: class WipLimitExceededError extends Error {}
}));

/**
 * The keys on the card, which are the only way to move it without a pointer.
 *
 * HTML5 drag-and-drop cannot be driven from a keyboard at all, so before this the board's central
 * gesture was unavailable to anyone not using a mouse. These assert the bindings rather than the
 * move: what happens after the drop is the context's, and is covered in keyboardMove.test.js.
 */
describe('moving a task with the keyboard', () => {
  const task = { id: '1', title: 'Test Task', userIds: [], labels: [], columnId: 'col1', rowId: 'row1' };

  const setUp = async (held = false) => {
    const keyboardMove = {
      isHeld: () => held,
      isTarget: () => false,
      grab: jest.fn(),
      step: jest.fn(),
      drop: jest.fn(),
      cancel: jest.fn(),
      announcement: null
    };

    await act(async () => {
      render(
        <KanbanContext.Provider value={{
          deleteTask: jest.fn(),
          refreshTasks: jest.fn(),
          updateTaskName: jest.fn(),
          updateTaskCompletion: jest.fn(),
          setDailyFocus: jest.fn(),
          dragAndDrop: {
            handleTaskReorder: jest.fn(),
            handleDragStart: jest.fn(),
            handleDragOver: jest.fn(),
            handleDrop: jest.fn(),
            handleDragEnd: jest.fn()
          },
          keyboardMove
        }}>
          <Task task={task} columnId="col1" rowId="row1" />
        </KanbanContext.Provider>
      );
    });

    return { keyboardMove, card: screen.getByRole('button', { name: 'Test Task' }) };
  };

  test('the card is reachable by keyboard and says what it is', async () => {
    // Without a tabIndex the card is not in the tab order at all, which is the state this branch
    // found it in: every key below is unreachable if nothing can focus the element.
    const { card } = await setUp();

    expect(card).toHaveAttribute('tabIndex', '0');
    expect(card).toHaveAttribute('aria-roledescription');
    expect(card).toHaveAttribute('aria-describedby', 'board-keyboard-move-help');
  });

  test('Space picks the card up, from the cell it is rendered in', async () => {
    const { keyboardMove, card } = await setUp();

    fireEvent.keyDown(card, { key: ' ' });

    expect(keyboardMove.grab).toHaveBeenCalledWith(task, 'col1', 'row1');
  });

  test('the arrow keys choose a cell while the card is held', async () => {
    const { keyboardMove, card } = await setUp(true);

    fireEvent.keyDown(card, { key: 'ArrowRight' });
    fireEvent.keyDown(card, { key: 'ArrowDown' });

    expect(keyboardMove.step).toHaveBeenNthCalledWith(1, 'right');
    expect(keyboardMove.step).toHaveBeenNthCalledWith(2, 'down');
  });

  test('Space and Enter both drop the held card, and Escape puts it back', async () => {
    const { keyboardMove, card } = await setUp(true);

    fireEvent.keyDown(card, { key: ' ' });
    fireEvent.keyDown(card, { key: 'Enter' });
    fireEvent.keyDown(card, { key: 'Escape' });

    expect(keyboardMove.drop).toHaveBeenCalledTimes(2);
    expect(keyboardMove.cancel).toHaveBeenCalledTimes(1);
    expect(keyboardMove.grab).not.toHaveBeenCalled();
  });

  test('the arrow keys do nothing when no card is held', async () => {
    // Otherwise every arrow press on a focused card would be swallowed, and the page would stop
    // scrolling for a move that is not happening.
    const { keyboardMove, card } = await setUp(false);

    fireEvent.keyDown(card, { key: 'ArrowRight' });

    expect(keyboardMove.step).not.toHaveBeenCalled();
  });

  test('a key pressed inside the card, rather than on it, is left alone', async () => {
    // The inline title editor and the buttons live inside this element. Space in a text field is
    // a space, and picking the card up instead would make the title uneditable by keyboard.
    const { keyboardMove, card } = await setUp();
    const inner = card.querySelector('.task-complete-checkbox');

    fireEvent.keyDown(inner, { key: ' ' });

    expect(keyboardMove.grab).not.toHaveBeenCalled();
  });

  test('Enter opens the task when nothing is held', async () => {
    const { keyboardMove, card } = await setUp(false);

    fireEvent.keyDown(card, { key: 'Enter' });

    expect(keyboardMove.grab).not.toHaveBeenCalled();
    expect(keyboardMove.drop).not.toHaveBeenCalled();
  });
});
