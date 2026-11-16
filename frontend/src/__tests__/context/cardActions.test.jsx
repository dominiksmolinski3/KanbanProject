import { renderHook } from '@testing-library/react';
import { useCardActions } from '../../context/kanban/cardActions';

const value = (overrides = {}) => ({
  deleteTask: jest.fn(),
  refreshTasks: jest.fn(),
  updateTaskName: jest.fn(),
  updateTaskCompletion: jest.fn(),
  setDailyFocus: jest.fn(),
  setDraggedTaskId: jest.fn(),
  dragAndDrop: { handleDrop: jest.fn(), draggedItem: null },
  keyboardMove: { held: null, grab: jest.fn(), isHeld: jest.fn(() => false) },
  tasks: [{ id: 1 }],
  ...overrides,
});

describe('useCardActions', () => {
  it('keeps one object and one set of functions for the life of the board', () => {
    const { result, rerender } = renderHook(({ latest }) => useCardActions(latest), { initialProps: { latest: value() } });
    const first = result.current;
    const { deleteTask, dragAndDrop, keyboardMove } = first;

    rerender({ latest: value() });

    expect(result.current).toBe(first);
    expect(result.current.deleteTask).toBe(deleteTask);
    expect(result.current.dragAndDrop).toBe(dragAndDrop);
    expect(result.current.dragAndDrop.handleDrop).toBe(dragAndDrop.handleDrop);
    expect(result.current.keyboardMove.grab).toBe(keyboardMove.grab);
  });

  it('calls the function of the latest render, with its arguments and its answer', () => {
    const { result, rerender } = renderHook(({ latest }) => useCardActions(latest), { initialProps: { latest: value() } });
    const later = value({ updateTaskName: jest.fn(() => 'renamed') });
    later.keyboardMove.isHeld = jest.fn(() => true);
    rerender({ latest: later });

    expect(result.current.updateTaskName(7, 'New')).toBe('renamed');
    expect(later.updateTaskName).toHaveBeenCalledWith(7, 'New');
    expect(result.current.keyboardMove.isHeld(7)).toBe(true);
  });

  it('reads a value that is not a function from the latest render', () => {
    const { result, rerender } = renderHook(({ latest }) => useCardActions(latest), { initialProps: { latest: value() } });
    const later = value();
    later.keyboardMove.held = { taskId: 7 };
    rerender({ latest: later });

    expect(result.current.keyboardMove.held).toEqual({ taskId: 7 });
  });

  it('carries the card actions only', () => {
    const { result } = renderHook(() => useCardActions(value()));

    expect(result.current.tasks).toBeUndefined();
  });
});
