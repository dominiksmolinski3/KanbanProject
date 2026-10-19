import React from 'react';
import { render, screen, waitFor } from '../../test-utils/rtl';
import userEvent from '@testing-library/user-event';
import '@testing-library/jest-dom';
import ParentTask from '../../components/taskDetails/relations/ParentTask';
import * as api from '../../services/api';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key) => key }),
}));
jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), success: jest.fn(), info: jest.fn(), warning: jest.fn() },
}));
jest.mock('../../services/api');

const tasks = [
  { id: 1, title: 'This task' },
  { id: 2, title: 'A child of it' },
  { id: 3, title: 'Unrelated' },
  { id: 4, title: 'Another' },
];

const renderParent = (props = {}) => {
  const onChanged = jest.fn();
  render(<ParentTask taskId={1} parentTaskId={null} readOnly={false} onChanged={onChanged} {...props} />);
  return { onChanged };
};

describe('ParentTask', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    api.fetchTasks.mockResolvedValue(tasks);
    api.getChildTasks.mockResolvedValue([tasks[1]]);
    api.fetchTask.mockResolvedValue({ id: 4, title: 'Another' });
    api.assignParentTask.mockResolvedValue({});
    api.removeParentTask.mockResolvedValue({});
  });

  test('offers every task except itself and its own children', async () => {
    const user = userEvent.setup();
    renderParent();

    await user.click(screen.getByText('taskDetails.assignParentTask'));

    const options = (await screen.findAllByRole('option')).map((option) => option.textContent);
    expect(options).toEqual(['taskDetails.chooseTask', 'Unrelated', 'Another']);
  });

  test('assigns the chosen task by its numeric id and reports the change', async () => {
    const user = userEvent.setup();
    const { onChanged } = renderParent();

    await user.click(screen.getByText('taskDetails.assignParentTask'));
    await user.selectOptions(await screen.findByLabelText('taskDetails.selectParentTask'), 'Unrelated');
    await user.click(screen.getByText('taskActions.assign'));

    await waitFor(() => expect(api.assignParentTask).toHaveBeenCalledWith(1, 3));
    expect(onChanged).toHaveBeenCalled();
    expect(screen.queryByLabelText('taskDetails.selectParentTask')).not.toBeInTheDocument();
  });

  test('keeps the Assign button off until a task is chosen', async () => {
    const user = userEvent.setup();
    renderParent();

    await user.click(screen.getByText('taskDetails.assignParentTask'));

    expect(await screen.findByText('taskActions.assign')).toBeDisabled();
  });

  test('shows the current parent and removes the link', async () => {
    const user = userEvent.setup();
    const { onChanged } = renderParent({ parentTaskId: 4 });

    expect(await screen.findByText('Another')).toBeInTheDocument();
    await user.click(screen.getByLabelText('taskDetails.removeParentLink'));

    await waitFor(() => expect(api.removeParentTask).toHaveBeenCalledWith(1));
    expect(onChanged).toHaveBeenCalled();
  });

  test('is read only when the board is', async () => {
    renderParent({ parentTaskId: 4, readOnly: true });

    expect(await screen.findByText('Another')).toBeInTheDocument();
    expect(screen.queryByLabelText('taskDetails.removeParentLink')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('taskDetails.changeParentTask')).not.toBeInTheDocument();
  });
});
