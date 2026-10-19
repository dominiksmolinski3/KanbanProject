import React from 'react';
import { render, screen, waitFor, act } from '../../test-utils/rtl';
import userEvent from '@testing-library/user-event';
import '@testing-library/jest-dom';
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

const task = { id: 1, title: 'Write docs', description: '', columnId: 10, rowId: 20, completed: false };

const READY = { timeout: 3000 };

const openPanel = async () => {
  const onClose = jest.fn();
  render(
    <KanbanContext.Provider value={{ refreshTasks: jest.fn(), readOnly: false, columns: [], rows: [], setDailyFocus: jest.fn(), updateTaskCompletion: jest.fn() }}>
      <TaskDetails task={task} onClose={onClose} onSubtaskUpdate={jest.fn()} />
    </KanbanContext.Provider>
  );
  await screen.findByRole('heading', { level: 2, name: 'Write docs' });
  return { onClose, dialog: screen.getByRole('dialog') };
};

const pickerOpened = (user) => user.click(screen.getByText('taskLabels.addLabel'));

describe('the label picker inside the task panel', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.fetchTask.mockResolvedValue({ ...task, version: 1, userIds: [], labels: ['Bug'] });
    api.fetchUsers.mockResolvedValue([]);
    api.fetchSubTasksByTaskId.mockResolvedValue([]);
    api.getUserAvatar.mockResolvedValue(null);
    api.getAllLabels.mockResolvedValue(['Bug', 'Epic']);
    api.updateTaskLabels.mockResolvedValue({ version: 2 });
    global.fetch = jest.fn().mockResolvedValue({ ok: true, json: () => Promise.resolve([]) });
    jest.spyOn(console, 'error').mockImplementation(() => {});
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  test('opens within the panel, so the modal counts it as part of itself', async () => {
    const user = userEvent.setup();
    const { dialog } = await openPanel();

    await pickerOpened(user);
    const header = await screen.findByText('taskLabels.chooseLabelHeader', {}, READY);

    expect(dialog).toContainElement(header);
  });

  test('can be clicked while the panel is open, and a click in it does not close the panel', async () => {
    const user = userEvent.setup();
    const { dialog, onClose } = await openPanel();

    await pickerOpened(user);
    await user.click(await screen.findByText('Epic', {}, READY));

    await waitFor(() => expect(api.updateTaskLabels).toHaveBeenCalledWith(1, ['Bug', 'Epic']));
    expect(onClose).not.toHaveBeenCalled();
    expect(dialog).toBeInTheDocument();
  });

  test('the custom label form takes typing without the panel pulling focus back', async () => {
    const user = userEvent.setup();
    const { dialog, onClose } = await openPanel();

    await pickerOpened(user);
    await user.click(await screen.findByText('taskLabels.addCustomLabel', {}, READY));
    const field = await screen.findByPlaceholderText('taskLabels.labelNamePlaceholder', {}, READY);
    await user.type(field, 'Spike');

    expect(field).toHaveValue('Spike');
    expect(dialog).toContainElement(field);
    expect(onClose).not.toHaveBeenCalled();
  });

  test('a custom label can be added from inside the panel', async () => {
    const user = userEvent.setup();
    await openPanel();

    await pickerOpened(user);
    await user.click(await screen.findByText('taskLabels.addCustomLabel', {}, READY));
    await user.type(await screen.findByPlaceholderText('taskLabels.labelNamePlaceholder', {}, READY), 'Spike');
    await user.click(screen.getByText('taskLabels.add'));

    await waitFor(() => expect(api.updateTaskLabels).toHaveBeenCalledWith(1, ['Bug', 'Spike']));
  });

  test('a press on the panel itself, away from the picker, closes the picker and keeps the panel', async () => {
    const user = userEvent.setup();
    const { onClose } = await openPanel();

    await pickerOpened(user);
    await screen.findByText('taskLabels.chooseLabelHeader', {}, READY);
    await act(async () => { await user.click(screen.getByRole('heading', { level: 2, name: 'Write docs' })); });

    await waitFor(() => expect(screen.queryByText('taskLabels.chooseLabelHeader')).not.toBeInTheDocument());
    expect(onClose).not.toHaveBeenCalled();
  });
});
