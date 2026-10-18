import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import { toast } from 'react-toastify';
import TaskDetails from '../../components/TaskDetails';
import KanbanContext from '../../context/KanbanContext';
import * as api from '../../services/api';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key, i18n: { resolvedLanguage: 'en' } }),
}));
jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), success: jest.fn(), info: jest.fn(), warning: jest.fn() },
}));
jest.mock('../../services/api');

const task = { id: 1, title: 'Write docs', description: 'Old words', columnId: 10, rowId: 20, completed: false, dailyFocus: false };

const contextValue = (overrides = {}) => ({
  refreshTasks: jest.fn(),
  readOnly: false,
  columns: [{ id: 10, name: 'Doing' }],
  rows: [{ id: 20, name: 'Backend' }],
  setDailyFocus: jest.fn(),
  updateTaskCompletion: jest.fn(),
  ...overrides,
});

const open = async (value = contextValue(), shown = task) => {
  render(
    <KanbanContext.Provider value={value}>
      <TaskDetails task={shown} onClose={jest.fn()} onSubtaskUpdate={jest.fn()} />
    </KanbanContext.Provider>
  );
  await screen.findByRole('heading', { level: 2, name: shown.title });
  return value;
};

describe('TaskDetails editing', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.fetchTask.mockResolvedValue({ ...task, version: 3, userIds: [1], labels: [] });
    api.fetchUsers.mockResolvedValue([{ id: 1, name: 'John Doe', email: 'john@example.com' }]);
    api.fetchSubTasksByTaskId.mockResolvedValue([]);
    api.getUserAvatar.mockResolvedValue(null);
    api.getAllLabels.mockResolvedValue([]);
    api.updateTask.mockResolvedValue({ version: 4 });
    api.removeUserFromTask.mockResolvedValue({});
    global.fetch = jest.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) });
    jest.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  test('editing the title saves it with the version it was loaded at, then shows the notice', async () => {
    await open();

    fireEvent.click(screen.getByLabelText('taskActions.editTitle'));
    fireEvent.change(screen.getByPlaceholderText('taskActions.editTitle'), { target: { value: 'Write better docs' } });
    fireEvent.click(screen.getByText('taskActions.save'));

    await waitFor(() => expect(api.updateTask).toHaveBeenCalledWith(1, { title: 'Write better docs', version: 3 }));
    expect(await screen.findByText('notifications.taskUpdated')).toBeInTheDocument();
    expect(screen.queryByPlaceholderText('taskActions.editTitle')).not.toBeInTheDocument();
  });

  test('cancelling a title edit puts the old title back', async () => {
    await open();

    fireEvent.click(screen.getByLabelText('taskActions.editTitle'));
    fireEvent.change(screen.getByPlaceholderText('taskActions.editTitle'), { target: { value: 'Something else' } });
    fireEvent.click(screen.getByText('taskActions.cancel'));

    expect(screen.getByRole('heading', { level: 2, name: 'Write docs' })).toBeInTheDocument();
    expect(api.updateTask).not.toHaveBeenCalled();
  });

  test('a blank title cannot be saved', async () => {
    await open();

    fireEvent.click(screen.getByLabelText('taskActions.editTitle'));
    fireEvent.change(screen.getByPlaceholderText('taskActions.editTitle'), { target: { value: '   ' } });

    expect(screen.getByText('taskActions.save')).toBeDisabled();
  });

  test('a failed save reports the error and leaves the field open to try again', async () => {
    api.updateTask.mockRejectedValue(new Error('boom'));
    await open();

    fireEvent.click(screen.getByLabelText('taskActions.editTitle'));
    fireEvent.change(screen.getByPlaceholderText('taskActions.editTitle'), { target: { value: 'New title' } });
    fireEvent.click(screen.getByText('taskActions.save'));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('notifications.errorOccurred'));
    expect(screen.getByPlaceholderText('taskActions.editTitle')).toHaveValue('New title');
  });

  test('editing the description saves it and closes the field', async () => {
    await open();

    fireEvent.click(screen.getByLabelText('taskActions.editTaskDescription'));
    fireEvent.change(screen.getByPlaceholderText('taskActions.description'), { target: { value: 'New words' } });
    fireEvent.click(screen.getByText('taskActions.save'));

    await waitFor(() => expect(api.updateTask).toHaveBeenCalledWith(1, { description: 'New words', version: 3 }));
    await waitFor(() => expect(screen.getByText('New words')).toBeInTheDocument());
  });

  test('cancelling a description edit shows the old text again', async () => {
    await open();

    fireEvent.click(screen.getByLabelText('taskActions.editTaskDescription'));
    fireEvent.change(screen.getByPlaceholderText('taskActions.description'), { target: { value: 'Discarded' } });
    fireEvent.click(screen.getByText('taskActions.cancel'));

    expect(screen.getByText('Old words')).toBeInTheDocument();
  });

  test('the star and the checkbox call the board', async () => {
    const value = await open();

    fireEvent.click(screen.getByLabelText('taskActions.addToDailyFocus'));
    fireEvent.click(screen.getByLabelText('taskActions.complete'));

    expect(value.setDailyFocus).toHaveBeenCalledWith(1, true);
    expect(value.updateTaskCompletion).toHaveBeenCalledWith(1, true);
  });

  test('on a read-only board there is nothing to edit and the checkbox is disabled', async () => {
    await open(contextValue({ readOnly: true }));

    expect(screen.queryByLabelText('taskActions.editTitle')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('taskActions.editTaskDescription')).not.toBeInTheDocument();
    expect(screen.getByLabelText('taskActions.complete')).toBeDisabled();
  });

  test('a failed removal of an assignee says so and closes the confirmation', async () => {
    api.removeUserFromTask.mockRejectedValue(new Error('nope'));
    await open();

    fireEvent.click((await screen.findAllByTitle('forms.deleteUser'))[0]);
    fireEvent.click(await screen.findByText('taskActions.yes'));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('notifications.userRemoveError'));
    await waitFor(() => expect(screen.queryByText('taskActions.confirmDeleteAssignedUser')).not.toBeInTheDocument());
  });

  test('Escape closes the panel', async () => {
    const onClose = jest.fn();
    render(
      <KanbanContext.Provider value={contextValue()}>
        <TaskDetails task={task} onClose={onClose} onSubtaskUpdate={jest.fn()} />
      </KanbanContext.Provider>
    );
    await screen.findByRole('heading', { level: 2, name: 'Write docs' });

    await act(async () => { fireEvent.keyDown(document, { key: 'Escape' }); });

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
