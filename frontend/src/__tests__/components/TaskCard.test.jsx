import React from 'react';
import { render, screen, act, waitFor, fireEvent } from '@testing-library/react';
import KanbanContext from '../../context/KanbanContext';
import Task from '../../components/Task';
import { fetchTask, getChildTasks, getUserAvatar } from '../../services/api';
import { clearAvatarCache } from '../../board/useUserAvatar';

jest.mock('../../services/api', () => ({
  getUserAvatar: jest.fn().mockResolvedValue(null),
  assignUserToTask: jest.fn().mockResolvedValue({}),
  fetchSubTasksByTaskId: jest.fn().mockResolvedValue([]),
  fetchTask: jest.fn().mockResolvedValue({}),
  getChildTasks: jest.fn().mockResolvedValue([]),
  WipLimitExceededError: class WipLimitExceededError extends Error {}
}));

jest.mock('react-xarrows', () => function MockXarrow({ start, end }) {
  return <span data-testid="xarrow" data-start={start} data-end={end} />;
});

const context = (overrides = {}) => ({
  deleteTask: jest.fn(),
  refreshTasks: jest.fn(),
  updateTaskName: jest.fn(),
  updateTaskCompletion: jest.fn(),
  setDailyFocus: jest.fn(),
  dragAndDrop: { handleTaskReorder: jest.fn() },
  keyboardMove: { isHeld: () => false, isTarget: () => false, grab: jest.fn(), step: jest.fn(), drop: jest.fn(), cancel: jest.fn() },
  activeBoard: { members: [{ id: 1, name: 'Ada Lovelace' }, { id: 2, name: 'Grace Hopper' }] },
  readOnly: false,
  ...overrides,
});

const baseTask = { id: 9, title: 'Ship it', columnId: 'c1', rowId: 'r1', userIds: [], labels: [] };

async function renderCard(task, value = context()) {
  let result;
  await act(async () => {
    result = render(
      <KanbanContext.Provider value={value}>
        <Task task={{ ...baseTask, ...task }} columnId="c1" rowId="r1" />
      </KanbanContext.Provider>
    );
  });
  return result;
}

describe('task card', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    clearAvatarCache();
  });

  test('mounting a card makes no per-card request for its relationships', async () => {
    await renderCard({ childTaskIds: [2], parentTaskId: 1 });
    expect(fetchTask).not.toHaveBeenCalled();
    expect(getChildTasks).not.toHaveBeenCalled();
  });

  test('while a parent is dragged or held, its children and its parent say how they are linked', async () => {
    const tasks = [
      { id: 1, title: 'Parent', childTaskIds: [9] },
      { id: 9, title: 'Ship it', parentTaskId: 1, childTaskIds: [] },
    ];
    const { unmount } = await renderCard({ parentTaskId: 1 }, context({ tasks, linkFocusId: 1 }));
    expect(document.querySelector('.task')).toHaveClass('linked-child');
    expect(screen.getByText('taskDetails.childTask')).toBeInTheDocument();
    unmount();

    await renderCard({ id: 1, childTaskIds: [9] }, context({ tasks: [{ id: 9, parentTaskId: 1 }], linkFocusId: 9 }));
    expect(document.querySelector('.task')).toHaveClass('linked-parent');
    expect(screen.getByText('taskDetails.parentTask')).toBeInTheDocument();
  });

  test('a card with relations carries a link chip; one without has none', async () => {
    const { unmount } = await renderCard({ childTaskIds: [2, 3] });
    expect(document.querySelector('.relation-chip')).toHaveTextContent('2');
    unmount();

    await renderCard({});
    expect(document.querySelector('.relation-chip')).toBeNull();
  });

  test('one click on the title opens the task; a double click edits the title instead', async () => {
    jest.useFakeTimers();
    try {
      await renderCard();
      const title = document.querySelector('.task .editable-text');

      fireEvent.click(title, { detail: 1 });
      fireEvent.click(title, { detail: 2 });
      await act(async () => { jest.advanceTimersByTime(500); });
      expect(fetchTask).not.toHaveBeenCalled();

      fireEvent.click(title, { detail: 1 });
      await act(async () => { jest.advanceTimersByTime(500); });
      expect(fetchTask).toHaveBeenCalledWith(9);
    } finally {
      jest.useRealTimers();
    }
  });

  test('assignees stack up to three, then a count, with initials when there is no picture', async () => {
    await renderCard({ userIds: [1, 2, 3, 4] });

    await waitFor(() => expect(getUserAvatar).toHaveBeenCalledTimes(3));
    const avatars = document.querySelectorAll('.avatar-stack .avatar-preview');
    expect(avatars).toHaveLength(3);
    expect(screen.getByRole('img', { name: 'Ada Lovelace' })).toHaveTextContent('AL');
    expect(screen.getByRole('img', { name: 'Grace Hopper' })).toHaveTextContent('GH');
    expect(screen.getByText('+1')).toHaveClass('avatar-count');
  });

  test('one account on many cards is fetched once', async () => {
    await act(async () => {
      render(
        <KanbanContext.Provider value={context()}>
          <Task task={{ ...baseTask, id: 1, userIds: [1] }} columnId="c1" />
          <Task task={{ ...baseTask, id: 2, userIds: [1] }} columnId="c1" />
          <Task task={{ ...baseTask, id: 3, userIds: [1] }} columnId="c1" />
        </KanbanContext.Provider>
      );
    });
    expect(getUserAvatar).toHaveBeenCalledTimes(1);
  });

  test('an overdue deadline is a red chip and marks the card', async () => {
    await renderCard({ deadline: '2020-01-01T10:00:00' });
    expect(document.querySelector('.due-chip')).toHaveClass('due-overdue');
    expect(document.querySelector('.task')).toHaveClass('deadline-expired');
  });

  test('a deadline within two days is flagged as upcoming', async () => {
    const soon = new Date(Date.now() + 5 * 60 * 60 * 1000).toISOString();
    await renderCard({ deadline: soon });
    expect(document.querySelector('.due-chip')).toHaveClass('due-soon');
    expect(document.querySelector('.task')).toHaveClass('deadline-upcoming');
  });

  test('subtasks are shown as done of total with a proportional bar', async () => {
    await renderCard({ openSubtasks: 1, subtaskCount: 4 });
    const chip = document.querySelector('.subtask-chip');
    expect(chip).toHaveTextContent('3/4');
    expect(chip).not.toHaveClass('subtask-done');
    expect(chip.querySelector('.subtask-bar > span')).toHaveStyle({ width: '75%' });
    expect(screen.getByText('taskActions.subtaskProgress')).toHaveClass('visually-hidden');
  });

  test('a card whose subtasks are all done still shows them, marked done', async () => {
    await renderCard({ openSubtasks: 0, subtaskCount: 2 });
    expect(document.querySelector('.subtask-chip')).toHaveClass('subtask-done');
    expect(document.querySelector('.subtask-chip')).toHaveTextContent('2/2');
  });

  test('labels beyond three collapse into a count and a priority label becomes the priority pill', async () => {
    await renderCard({ labels: ['urgent', 'a', 'b', 'c', 'd'] });
    expect(document.querySelector('.task-priority-pill')).toHaveClass('priority-urgent');
    expect(document.querySelectorAll('.task-label-pill')).toHaveLength(3);
    expect(document.querySelector('.task-label-count')).toHaveTextContent('+1');
  });

  test('a card with nothing to say renders no metadata row', async () => {
    await renderCard({});
    expect(document.querySelector('.task-meta')).toBeNull();
  });

  test('dragging draws arrows only to relatives that are on the board', async () => {
    const other = document.createElement('div');
    other.id = 'task-2';
    document.body.appendChild(other);

    await renderCard({ childTaskIds: [2, 3], parentTaskId: 4 });
    fireEvent.dragStart(document.querySelector('.task'), {
      dataTransfer: { setData: jest.fn(), effectAllowed: '' },
    });

    const arrows = screen.getAllByTestId('xarrow');
    expect(arrows).toHaveLength(1);
    expect(arrows[0]).toHaveAttribute('data-end', 'task-2');
    other.remove();
  });

  test('the drag image is a tilted copy of the card that leaves the DOM after the snapshot', async () => {
    await renderCard({});
    const setDragImage = jest.fn();
    let frame;
    const raf = jest.spyOn(window, 'requestAnimationFrame').mockImplementation((cb) => { frame = cb; return 1; });

    fireEvent.dragStart(document.querySelector('.task'), {
      dataTransfer: { setData: jest.fn(), setDragImage, effectAllowed: '' },
    });

    const ghost = setDragImage.mock.calls[0][0];
    expect(ghost).toHaveClass('task', 'task-drag-ghost');
    expect(document.body.contains(ghost)).toBe(true);
    frame();
    expect(document.body.contains(ghost)).toBe(false);
    raf.mockRestore();
  });
});
