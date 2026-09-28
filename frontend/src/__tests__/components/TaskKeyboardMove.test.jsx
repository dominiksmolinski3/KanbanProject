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

    return {
      keyboardMove,
      card: screen.getByRole('article'),
      grip: screen.getByRole('button', { name: 'board.keyboardMove.grip' })
    };
  };

  test('the card is an article named by its title, not a button wrapping other controls', async () => {
    const { card } = await setUp();

    expect(card.tagName).toBe('ARTICLE');
    expect(card).not.toHaveAttribute('role');
    expect(card).not.toHaveAttribute('tabIndex');
    expect(card).toHaveAccessibleName('Test Task');
  });

  test('the grip is the keyboard handle and says what it moves', async () => {
    const { grip } = await setUp();

    expect(grip.tagName).toBe('BUTTON');
    expect(grip).toHaveAttribute('aria-roledescription');
    expect(grip).toHaveAttribute('aria-describedby', 'board-keyboard-move-help');
    expect(grip).toHaveAttribute('aria-pressed', 'false');
  });

  test('Space picks the card up, from the cell it is rendered in', async () => {
    const { keyboardMove, grip } = await setUp();

    fireEvent.keyDown(grip, { key: ' ' });

    expect(keyboardMove.grab).toHaveBeenCalledWith(task, 'col1', 'row1');
  });

  test('the arrow keys choose a cell while the card is held', async () => {
    const { keyboardMove, grip } = await setUp(true);

    fireEvent.keyDown(grip, { key: 'ArrowRight' });
    fireEvent.keyDown(grip, { key: 'ArrowDown' });

    expect(keyboardMove.step).toHaveBeenNthCalledWith(1, 'right');
    expect(keyboardMove.step).toHaveBeenNthCalledWith(2, 'down');
  });

  test('Space and Enter both drop the held card, and Escape puts it back', async () => {
    const { keyboardMove, grip } = await setUp(true);

    fireEvent.keyDown(grip, { key: ' ' });
    fireEvent.keyDown(grip, { key: 'Enter' });
    fireEvent.keyDown(grip, { key: 'Escape' });

    expect(keyboardMove.drop).toHaveBeenCalledTimes(2);
    expect(keyboardMove.cancel).toHaveBeenCalledTimes(1);
    expect(keyboardMove.grab).not.toHaveBeenCalled();
  });

  test('the arrow keys do nothing when no card is held', async () => {
    const { keyboardMove, grip } = await setUp(false);

    fireEvent.keyDown(grip, { key: 'ArrowRight' });

    expect(keyboardMove.step).not.toHaveBeenCalled();
  });

  test('a key pressed on another control in the card is left alone', async () => {
    const { keyboardMove, card } = await setUp();

    fireEvent.keyDown(card.querySelector('.task-complete-checkbox'), { key: ' ' });
    fireEvent.keyDown(card, { key: ' ' });

    expect(keyboardMove.grab).not.toHaveBeenCalled();
  });

  test('the open button is a real button that opens the task', async () => {
    const { card } = await setUp();

    fireEvent.click(screen.getByRole('button', { name: 'taskActions.open' }));

    expect(card).toBeInTheDocument();
    expect(document.querySelector('.task-details-overlay')).toBeInTheDocument();
  });

  test('Enter opens the task when nothing is held', async () => {
    const { keyboardMove, grip } = await setUp(false);

    fireEvent.keyDown(grip, { key: 'Enter' });

    expect(keyboardMove.grab).not.toHaveBeenCalled();
    expect(keyboardMove.drop).not.toHaveBeenCalled();
  });
});
