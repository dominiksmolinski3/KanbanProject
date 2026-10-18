import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import TaskLabels from '../../components/TaskLabels';
import { updateTaskLabels, getAllLabels } from '../../services/api';
import KanbanContext from '../../context/KanbanContext';

jest.mock('../../services/api', () => ({
  updateTaskLabels: jest.fn(),
  getAllLabels: jest.fn(),
}));
jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), success: jest.fn(), info: jest.fn(), warning: jest.fn() },
}));
jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));
jest.mock('react-dom', () => ({
  ...jest.requireActual('react-dom'),
  createPortal: (node) => node,
}));

const renderLabels = async (onLabelsChange = jest.fn()) => {
  await act(async () => {
    render(
      <KanbanContext.Provider value={{ refreshTasks: jest.fn() }}>
        <TaskLabels taskId={1} initialLabels={['Bug']} onLabelsChange={onLabelsChange} />
      </KanbanContext.Provider>
    );
  });
  return onLabelsChange;
};

const openPicker = async () => {
  await act(async () => { fireEvent.click(screen.getByText('taskLabels.addLabel')); });
  await screen.findByText('taskLabels.chooseLabelHeader');
};

describe('TaskLabels picker', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    localStorage.clear();
    getAllLabels.mockResolvedValue(['Bug', 'Epic']);
    updateTaskLabels.mockResolvedValue({ version: 2 });
  });

  test('offers the labels other cards use, apart from the predefined ones, and adds one on a click', async () => {
    const onLabelsChange = await renderLabels();
    await openPicker();

    expect(screen.getByText('taskLabels.existingLabels')).toBeInTheDocument();
    await act(async () => { fireEvent.click(screen.getByText('Epic')); });

    await waitFor(() => expect(updateTaskLabels).toHaveBeenCalledWith(1, ['Bug', 'Epic']));
    expect(onLabelsChange).toHaveBeenCalledWith(['Bug', 'Epic'], { version: 2 });
    await waitFor(() => expect(screen.queryByText('taskLabels.chooseLabelHeader')).not.toBeInTheDocument());
  });

  test('a press on the add button itself is not an outside click, so the next click closes the picker', async () => {
    await renderLabels();
    await openPicker();
    const addButton = screen.getByText('taskLabels.addLabel');

    await act(async () => { fireEvent.mouseDown(addButton); });
    await act(async () => { fireEvent.click(addButton); });
    await act(async () => { await new Promise((resolve) => setTimeout(resolve, 120)); });

    expect(screen.queryByText('taskLabels.chooseLabelHeader')).not.toBeInTheDocument();
  });

  test('a press inside the picker does not close it', async () => {
    await renderLabels();
    await openPicker();

    await act(async () => { fireEvent.mouseDown(screen.getByText('taskLabels.chooseLabelHeader')); });

    expect(screen.getByText('taskLabels.chooseLabelHeader')).toBeInTheDocument();
  });

  test('the custom form keeps the name that was typed when it is cancelled and opened again', async () => {
    await renderLabels();
    await openPicker();

    await act(async () => { fireEvent.click(screen.getByText('taskLabels.addCustomLabel')); });
    const field = await screen.findByPlaceholderText('taskLabels.labelNamePlaceholder');
    fireEvent.change(field, { target: { value: 'Spike' } });
    await act(async () => { fireEvent.click(screen.getByText('taskLabels.cancel')); });
    expect(screen.queryByPlaceholderText('taskLabels.labelNamePlaceholder')).not.toBeInTheDocument();

    await openPicker();
    await act(async () => { fireEvent.click(screen.getByText('taskLabels.addCustomLabel')); });

    expect(await screen.findByPlaceholderText('taskLabels.labelNamePlaceholder')).toHaveValue('Spike');
  });

  test('a custom label is saved with its colour and the form is emptied', async () => {
    await renderLabels();
    await openPicker();

    await act(async () => { fireEvent.click(screen.getByText('taskLabels.addCustomLabel')); });
    fireEvent.change(await screen.findByPlaceholderText('taskLabels.labelNamePlaceholder'), { target: { value: 'Spike' } });
    await act(async () => { fireEvent.click(screen.getByText('taskLabels.add')); });

    await waitFor(() => expect(updateTaskLabels).toHaveBeenCalledWith(1, ['Bug', 'Spike']));
    await waitFor(() => expect(JSON.parse(localStorage.getItem('labelColors')).Spike).toBe('#888888'));
    await waitFor(() => expect(screen.queryByPlaceholderText('taskLabels.labelNamePlaceholder')).not.toBeInTheDocument());
  });
});
