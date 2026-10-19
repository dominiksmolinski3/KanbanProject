import React from 'react';
import { render, screen, fireEvent, waitFor, act } from '../../test-utils/rtl';
import '@testing-library/jest-dom';
import { toast } from 'react-toastify';
import Bench from '../../components/Bench';
import KanbanContext from '../../context/KanbanContext';
import { fetchUsers, getUserAvatar, updateUserWipLimit } from '../../services/api';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key, values) => (values ? `${key}:${JSON.stringify(values)}` : key) }),
}));
jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), success: jest.fn() },
}));
jest.mock('../../services/api', () => ({
  fetchUsers: jest.fn(),
  getUserAvatar: jest.fn(),
  updateUserWipLimit: jest.fn(),
}));

URL.revokeObjectURL = jest.fn();

const renderBench = async () => {
  const refreshTasks = jest.fn();
  let view;
  await act(async () => {
    view = render(
      <KanbanContext.Provider value={{ refreshTasks }}>
        <Bench />
      </KanbanContext.Provider>
    );
  });
  await screen.findByText('Ada');
  return { refreshTasks, ...view };
};

const editorFor = (name) => screen.getByText(name).closest('.user-info');

describe('Bench', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    fetchUsers.mockResolvedValue([
      { id: 1, name: 'Ada', email: 'ada@example.com', wipLimit: 5, taskCount: 0 },
      { id: 2, name: 'Bob', email: 'bob@example.com', wipLimit: 2, taskCount: 2 },
    ]);
    getUserAvatar.mockResolvedValue(null);
    updateUserWipLimit.mockResolvedValue({});
  });

  test('a person with nothing assigned shows no count row, and no stray zero', async () => {
    await renderBench();

    expect(editorFor('Ada').querySelector('.user-task-count')).not.toBeInTheDocument();
    expect(editorFor('Ada')).not.toHaveTextContent(/(^|[^\d])0$/);
    expect(editorFor('Bob').querySelector('.user-task-count')).toHaveClass('limit-reached');
  });

  test('picture links are kept while the others arrive and let go only when the bench goes', async () => {
    getUserAvatar.mockImplementation(async (id) => `blob:avatar-${id}`);
    const { unmount } = await renderBench();
    await waitFor(() => expect(screen.getAllByRole('img')).toHaveLength(2));

    expect(URL.revokeObjectURL).not.toHaveBeenCalled();

    unmount();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:avatar-1');
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:avatar-2');
  });

  test('a link that is not a blob is not revoked', async () => {
    getUserAvatar.mockResolvedValue('data:image/png;base64,iVBORw0KGgo=');
    const { unmount } = await renderBench();
    await waitFor(() => expect(screen.getAllByRole('img')[0]).toHaveAttribute('src', 'data:image/png;base64,iVBORw0KGgo='));

    unmount();

    expect(URL.revokeObjectURL).not.toHaveBeenCalled();
  });

  test('emptying the field and saving sends no limit at all', async () => {
    const { refreshTasks } = await renderBench();

    fireEvent.click(editorFor('Ada').querySelector('.wip-limit-display'));
    fireEvent.change(editorFor('Ada').querySelector('input'), { target: { value: '' } });
    await act(async () => { fireEvent.click(editorFor('Ada').querySelector('.save-wip-btn')); });

    expect(updateUserWipLimit).toHaveBeenCalledWith(1, null);
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('bench.unlimited'));
    expect(editorFor('Ada').querySelector('.wip-limit-display')).toHaveTextContent('∞');
    expect(refreshTasks).toHaveBeenCalled();
  });

  test('a refused save keeps the editor open with what was typed', async () => {
    updateUserWipLimit.mockRejectedValue(new Error('no'));
    jest.spyOn(console, 'error').mockImplementation(() => {});
    await renderBench();

    fireEvent.click(editorFor('Ada').querySelector('.wip-limit-display'));
    fireEvent.change(editorFor('Ada').querySelector('input'), { target: { value: '9' } });
    await act(async () => { fireEvent.click(editorFor('Ada').querySelector('.save-wip-btn')); });

    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('notifications.wipLimitUpdateError'));
    expect(editorFor('Ada').querySelector('input')).toHaveValue(9);
  });

  test('cancelling puts the saved limit back, not what was typed', async () => {
    await renderBench();

    fireEvent.click(editorFor('Ada').querySelector('.wip-limit-display'));
    fireEvent.change(editorFor('Ada').querySelector('input'), { target: { value: '9' } });
    fireEvent.click(editorFor('Ada').querySelector('.cancel-wip-btn'));
    fireEvent.click(editorFor('Ada').querySelector('.wip-limit-display'));

    expect(editorFor('Ada').querySelector('input')).toHaveValue(5);
  });

  test('editing one person leaves the others as they were', async () => {
    await renderBench();

    fireEvent.click(editorFor('Ada').querySelector('.wip-limit-display'));

    expect(editorFor('Ada').querySelector('input')).toBeInTheDocument();
    expect(editorFor('Bob').querySelector('input')).not.toBeInTheDocument();
  });
});
