import React from 'react';
import { render, screen, fireEvent, act, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import { toast } from 'react-toastify';
import KanbanContext from '../../context/KanbanContext';
import Task from '../../components/Task';
import { assignUserToTask, WipLimitExceededError } from '../../services/api';

jest.mock('react-toastify', () => ({ toast: { success: jest.fn(), error: jest.fn() } }));
jest.mock('../../services/api', () => ({
  ...jest.requireActual('../../services/api'),
  assignUserToTask: jest.fn(),
  getUserAvatar: jest.fn().mockResolvedValue(null),
}));

const baseTask = { id: 1, title: 'Write docs', userIds: [], columnId: 10, rowId: 20, openSubtasks: 0 };

const contextValue = (overrides = {}) => ({
  deleteTask: jest.fn(),
  refreshTasks: jest.fn(),
  updateTaskName: jest.fn(),
  setDraggedTaskId: jest.fn(),
  readOnly: false,
  dragAndDrop: { handleTaskReorder: jest.fn() },
  keyboardMove: { isHeld: () => false, grab: jest.fn(), step: jest.fn(), drop: jest.fn(), cancel: jest.fn() },
  ...overrides,
});

const renderTask = (task = baseTask, value = contextValue()) => {
  render(
    <KanbanContext.Provider value={value}>
      <Task task={task} columnId={10} rowId={20} />
    </KanbanContext.Provider>
  );
  return { value, card: document.getElementById(`task-${task.id}`) };
};

const transfer = (entries = {}) => ({
  types: Object.keys(entries),
  getData: (type) => entries[type],
  setData: jest.fn(),
  setDragImage: jest.fn(),
  effectAllowed: null,
});

const userDrop = (payload) => transfer({ 'application/user': typeof payload === 'string' ? payload : JSON.stringify(payload) });

describe('dragging a task card', () => {
  afterEach(() => {
    jest.useRealTimers();
    document.body.classList.remove('showing-task-relationships');
  });

  test('a read-only board does not start a drag', () => {
    const { card } = renderTask(baseTask, contextValue({ readOnly: true }));
    const dataTransfer = transfer();

    fireEvent.dragStart(card, { dataTransfer });

    expect(dataTransfer.setData).not.toHaveBeenCalled();
    expect(card).not.toHaveClass('dragging');
  });

  test('starting and ending a drag marks the card and tells the board which one is dragged', () => {
    const { card, value } = renderTask();

    fireEvent.dragStart(card, { dataTransfer: transfer() });
    expect(card).toHaveClass('dragging');
    expect(document.body).toHaveClass('showing-task-relationships');
    expect(value.setDraggedTaskId).toHaveBeenLastCalledWith(1);

    fireEvent.dragEnd(card);
    expect(card).not.toHaveClass('dragging');
    expect(document.body).not.toHaveClass('showing-task-relationships');
    expect(value.setDraggedTaskId).toHaveBeenLastCalledWith(null);
  });

  test('the drag data names the card and where it came from', () => {
    const { card } = renderTask();
    const dataTransfer = transfer();

    fireEvent.dragStart(card, { dataTransfer });

    const [, payload] = dataTransfer.setData.mock.calls.find(([type]) => type === 'application/task');
    expect(JSON.parse(payload)).toEqual({ id: 1, type: 'task', sourceColumnId: 10, sourceRowId: 20 });
  });

  test('a card with open subtasks warns when it is picked up, and the warning fades', () => {
    jest.useFakeTimers();
    const { card } = renderTask({ ...baseTask, openSubtasks: 2 });

    fireEvent.dragStart(card, { dataTransfer: transfer() });
    expect(screen.getByText('taskActions.incompleteSubtasks')).toBeInTheDocument();

    act(() => jest.advanceTimersByTime(5000));
    expect(screen.queryByText('taskActions.incompleteSubtasks')).not.toBeInTheDocument();
  });

  test('picking the card up again restarts the warning rather than stacking timers', () => {
    jest.useFakeTimers();
    const { card } = renderTask({ ...baseTask, openSubtasks: 1 });

    fireEvent.dragStart(card, { dataTransfer: transfer() });
    act(() => jest.advanceTimersByTime(3000));
    fireEvent.dragStart(card, { dataTransfer: transfer() });
    act(() => jest.advanceTimersByTime(3000));

    expect(screen.getByText('taskActions.incompleteSubtasks')).toBeInTheDocument();
  });

  test('the warning can be dismissed, and ending the drag also removes it', () => {
    const { card } = renderTask({ ...baseTask, openSubtasks: 1 });

    fireEvent.dragStart(card, { dataTransfer: transfer() });
    fireEvent.click(screen.getByLabelText('demo.dismiss'));
    expect(screen.queryByText('taskActions.incompleteSubtasks')).not.toBeInTheDocument();

    fireEvent.dragStart(card, { dataTransfer: transfer() });
    expect(screen.getByText('taskActions.incompleteSubtasks')).toBeInTheDocument();
    fireEvent.dragEnd(card);
    expect(screen.queryByText('taskActions.incompleteSubtasks')).not.toBeInTheDocument();
  });
});

describe('dropping onto a task card', () => {
  beforeEach(() => jest.clearAllMocks());
  afterEach(() => jest.useRealTimers());

  test('a task or a user passing over lights the card up, anything else does not', () => {
    const { card } = renderTask();

    fireEvent.dragOver(card, { dataTransfer: transfer({ 'text/plain': 'x' }) });
    expect(card).not.toHaveClass('user-drag-over');

    fireEvent.dragOver(card, { dataTransfer: transfer({ 'application/user': '{}' }) });
    expect(card).toHaveClass('user-drag-over');

    fireEvent.dragLeave(card, { dataTransfer: transfer() });
    expect(card).not.toHaveClass('user-drag-over');
  });

  test('dropping another card asks the board to put it here', () => {
    const { card, value } = renderTask();

    fireEvent.drop(card, { dataTransfer: transfer({ 'application/task': JSON.stringify({ id: 2 }) }) });

    expect(value.dragAndDrop.handleTaskReorder).toHaveBeenCalledWith(2, 1);
  });

  test('dropping a card on itself does nothing', () => {
    const { card, value } = renderTask();

    fireEvent.drop(card, { dataTransfer: transfer({ 'application/task': JSON.stringify({ id: 1 }) }) });

    expect(value.dragAndDrop.handleTaskReorder).not.toHaveBeenCalled();
  });

  test('a read-only board ignores every drop', async () => {
    const { card, value } = renderTask(baseTask, contextValue({ readOnly: true }));

    await act(async () => {
      fireEvent.drop(card, { dataTransfer: userDrop({ type: 'user', userId: '3' }) });
    });

    expect(assignUserToTask).not.toHaveBeenCalled();
    expect(value.dragAndDrop.handleTaskReorder).not.toHaveBeenCalled();
  });

  test('dropping a user assigns them, refreshes the board and says so', async () => {
    assignUserToTask.mockResolvedValue({});
    const { card, value } = renderTask();

    await act(async () => {
      fireEvent.drop(card, { dataTransfer: userDrop({ type: 'user', userId: '3', userName: 'Ada' }) });
    });

    expect(assignUserToTask).toHaveBeenCalledWith(1, 3);
    expect(value.refreshTasks).toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalledWith('notifications.userAssignedToTask');
  });

  test('a user over their WIP limit is refused on the card, and the message fades', async () => {
    jest.useFakeTimers();
    assignUserToTask.mockRejectedValue(new WipLimitExceededError({ userId: 3, wipLimit: 2 }));
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
    const { card } = renderTask();

    await act(async () => {
      fireEvent.drop(card, { dataTransfer: userDrop({ type: 'user', userId: '3', userName: 'Ada' }) });
    });
    expect(screen.getByText('notifications.userWipLimitExceeded')).toBeInTheDocument();

    act(() => jest.advanceTimersByTime(5000));
    expect(screen.queryByText('notifications.userWipLimitExceeded')).not.toBeInTheDocument();
    errorSpy.mockRestore();
  });

  test('any other failure to assign shows the general message', async () => {
    assignUserToTask.mockRejectedValue(new Error('boom'));
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
    const { card } = renderTask();

    await act(async () => {
      fireEvent.drop(card, { dataTransfer: userDrop({ type: 'user', userId: '3' }) });
    });

    expect(screen.getByText('notifications.userAssignError')).toBeInTheDocument();
    errorSpy.mockRestore();
  });

  test('drop data that is not JSON shows the general message and assigns nobody', async () => {
    const errorSpy = jest.spyOn(console, 'error').mockImplementation(() => {});
    const { card } = renderTask();

    await act(async () => {
      fireEvent.drop(card, { dataTransfer: userDrop('not json') });
    });

    expect(screen.getByText('notifications.userAssignError')).toBeInTheDocument();
    expect(assignUserToTask).not.toHaveBeenCalled();
    errorSpy.mockRestore();
  });

  test('something dropped under the user type that is not a user is ignored', async () => {
    const { card } = renderTask();

    await act(async () => {
      fireEvent.drop(card, { dataTransfer: userDrop({ type: 'label' }) });
    });

    expect(assignUserToTask).not.toHaveBeenCalled();
  });
});

describe('deleting a task card', () => {
  test('Escape cancels the confirmation without deleting', async () => {
    const { value } = renderTask();

    fireEvent.click(screen.getByTitle('taskActions.delete'));
    expect(screen.getByText('taskActions.confirmDelete')).toBeInTheDocument();

    fireEvent.keyDown(document, { key: 'Escape' });
    await waitFor(() => expect(screen.queryByText('taskActions.confirmDelete')).not.toBeInTheDocument());
    expect(value.deleteTask).not.toHaveBeenCalled();
  });

  test('a click on the backdrop cancels it', async () => {
    renderTask();
    fireEvent.click(screen.getByTitle('taskActions.delete'));
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });

    fireEvent.pointerDown(document.querySelector('.delete-modal-overlay'));

    await waitFor(() => expect(screen.queryByText('taskActions.confirmDelete')).not.toBeInTheDocument());
  });

  test('the confirmation is an alert dialog and takes focus off the card', () => {
    renderTask();

    fireEvent.click(screen.getByTitle('taskActions.delete'));

    expect(screen.getByRole('alertdialog')).toHaveFocus();
  });
});
