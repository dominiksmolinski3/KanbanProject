import React from 'react';
import { render, screen, fireEvent, waitFor, within, act } from '@testing-library/react';
import '@testing-library/jest-dom';
import TaskComments from '../../components/TaskComments';
import * as api from '../../services/api';
import { toast } from 'react-toastify';

jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key, values) => (values ? `${key}:${JSON.stringify(values)}` : key)
  })
}));

jest.mock('react-toastify', () => ({
  toast: { error: jest.fn(), success: jest.fn() }
}));

jest.mock('../../services/api', () => ({
  fetchTaskComments: jest.fn(),
  addTaskComment: jest.fn(),
  editTaskComment: jest.fn(),
  deleteTaskComment: jest.fn(),
  MAX_COMMENT_LENGTH: 2000,
  COMMENT_PAGE_SIZE: 25
}));

const mockKanban = { readOnly: false, activeBoard: { id: 3, owned: false } };
jest.mock('../../context/KanbanContext', () => ({
  useKanban: () => mockKanban
}));

const mockAuth = { user: { id: 1, name: 'Ada' } };
jest.mock('../../context/AuthContext', () => ({
  useAuth: () => mockAuth
}));

const comment = (id, authorId, body, extra = {}) => ({
  id, taskId: 7, body, authorId, authorName: authorId ? `User ${authorId}` : null,
  createdAt: '2026-09-22T10:00:00Z', editedAt: null, ...extra
});

const page = (comments, totalEntries = comments.length) => ({
  comments, page: 0, size: 25, totalEntries, totalPages: 1
});

describe('TaskComments', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockKanban.readOnly = false;
    mockKanban.activeBoard = { id: 3, owned: false };
    mockAuth.user = { id: 1, name: 'Ada' };
    console.error = jest.fn();
  });

  test('reads the thread newest first, naming each author', async () => {
    api.fetchTaskComments.mockResolvedValue(page([comment(2, 2, 'second'), comment(1, 1, 'first')]));

    render(<TaskComments taskId={7} />);

    expect(await screen.findByText('second')).toBeInTheDocument();
    const items = screen.getAllByRole('listitem');
    expect(within(items[0]).getByText('User 2')).toBeInTheDocument();
    expect(within(items[1]).getByText('first')).toBeInTheDocument();
    expect(api.fetchTaskComments).toHaveBeenCalledWith(7, { page: 0, size: 25 });
  });

  test('an empty thread says so', async () => {
    api.fetchTaskComments.mockResolvedValue(page([]));

    render(<TaskComments taskId={7} />);

    expect(await screen.findByText('taskComments.empty')).toBeInTheDocument();
  });

  test('a comment whose author has gone is attributed to a former member, and marked if edited', async () => {
    api.fetchTaskComments.mockResolvedValue(page([comment(1, null, 'orphan', { editedAt: '2026-09-22T11:00:00Z' })]));

    render(<TaskComments taskId={7} />);

    expect(await screen.findByText('taskComments.formerMember')).toBeInTheDocument();
    expect(screen.getByText('taskComments.edited')).toBeInTheDocument();
  });

  test('posting sends the trimmed text, clears the box and re-reads', async () => {
    api.fetchTaskComments.mockResolvedValue(page([]));
    api.addTaskComment.mockResolvedValue(comment(1, 1, 'hello'));

    render(<TaskComments taskId={7} />);
    await screen.findByText('taskComments.empty');

    const box = screen.getByPlaceholderText('taskComments.placeholder');
    fireEvent.change(box, { target: { value: '  hello  ' } });
    fireEvent.click(screen.getByText('taskComments.post'));

    await waitFor(() => expect(api.addTaskComment).toHaveBeenCalledWith(7, 'hello'));
    await waitFor(() => expect(box).toHaveValue(''));
    expect(api.fetchTaskComments).toHaveBeenCalledTimes(2);
  });

  test('a blank draft cannot be posted', async () => {
    api.fetchTaskComments.mockResolvedValue(page([]));

    render(<TaskComments taskId={7} />);
    await screen.findByText('taskComments.empty');

    fireEvent.change(screen.getByPlaceholderText('taskComments.placeholder'), { target: { value: '   ' } });
    expect(screen.getByText('taskComments.post')).toBeDisabled();
  });

  test('says how much room is left near the limit', async () => {
    api.fetchTaskComments.mockResolvedValue(page([]));

    render(<TaskComments taskId={7} />);
    await screen.findByText('taskComments.empty');

    fireEvent.change(screen.getByPlaceholderText('taskComments.placeholder'), { target: { value: 'x'.repeat(1900) } });
    expect(screen.getByText('taskComments.remaining:{"count":100}')).toBeInTheDocument();
  });

  test('a failed post is a toast, and the draft is kept', async () => {
    api.fetchTaskComments.mockResolvedValue(page([]));
    api.addTaskComment.mockRejectedValue(new Error('500'));

    render(<TaskComments taskId={7} />);
    await screen.findByText('taskComments.empty');

    const box = screen.getByPlaceholderText('taskComments.placeholder');
    fireEvent.change(box, { target: { value: 'keep me' } });
    fireEvent.click(screen.getByText('taskComments.post'));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('taskComments.postError'));
    expect(box).toHaveValue('keep me');
  });

  test('a viewer reads the thread and is offered nothing to write', async () => {
    mockKanban.readOnly = true;
    api.fetchTaskComments.mockResolvedValue(page([comment(1, 1, 'mine')]));

    render(<TaskComments taskId={7} />);
    await screen.findByText('mine');

    expect(screen.queryByPlaceholderText('taskComments.placeholder')).not.toBeInTheDocument();
    expect(screen.queryByText('taskComments.edit')).not.toBeInTheDocument();
    expect(screen.queryByText('taskComments.delete')).not.toBeInTheDocument();
  });

  test("only the author is offered edit, and a member cannot delete somebody else's", async () => {
    api.fetchTaskComments.mockResolvedValue(page([comment(2, 2, 'theirs'), comment(1, 1, 'mine')]));

    render(<TaskComments taskId={7} />);
    await screen.findByText('mine');

    const [theirs, mine] = screen.getAllByRole('listitem');
    expect(within(theirs).queryByText('taskComments.edit')).not.toBeInTheDocument();
    expect(within(theirs).queryByText('taskComments.delete')).not.toBeInTheDocument();
    expect(within(mine).getByText('taskComments.edit')).toBeInTheDocument();
    expect(within(mine).getByText('taskComments.delete')).toBeInTheDocument();
  });

  test("the board's owner may delete anybody's comment, after confirming", async () => {
    mockKanban.activeBoard = { id: 3, owned: true };
    api.fetchTaskComments.mockResolvedValue(page([comment(2, 2, 'theirs')]));
    api.deleteTaskComment.mockResolvedValue(true);

    render(<TaskComments taskId={7} />);
    await screen.findByText('theirs');

    fireEvent.click(screen.getByText('taskComments.delete'));
    expect(screen.getByText('taskComments.confirmDelete')).toBeInTheDocument();
    fireEvent.click(screen.getAllByText('taskComments.delete')[0]);

    await waitFor(() => expect(api.deleteTaskComment).toHaveBeenCalledWith(7, 2));
  });

  test('cancelling a delete keeps the comment', async () => {
    api.fetchTaskComments.mockResolvedValue(page([comment(1, 1, 'mine')]));

    render(<TaskComments taskId={7} />);
    await screen.findByText('mine');

    fireEvent.click(screen.getByText('taskComments.delete'));
    fireEvent.click(screen.getByText('taskComments.cancel'));

    expect(screen.queryByText('taskComments.confirmDelete')).not.toBeInTheDocument();
    expect(api.deleteTaskComment).not.toHaveBeenCalled();
  });

  test('a failed delete is a toast', async () => {
    api.fetchTaskComments.mockResolvedValue(page([comment(1, 1, 'mine')]));
    api.deleteTaskComment.mockRejectedValue(new Error('500'));

    render(<TaskComments taskId={7} />);
    await screen.findByText('mine');

    fireEvent.click(screen.getByText('taskComments.delete'));
    fireEvent.click(screen.getAllByText('taskComments.delete')[0]);

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('taskComments.deleteError'));
  });

  test('the author rewrites a comment in place', async () => {
    api.fetchTaskComments.mockResolvedValue(page([comment(1, 1, 'mine')]));
    api.editTaskComment.mockResolvedValue(comment(1, 1, 'better'));

    render(<TaskComments taskId={7} />);
    await screen.findByText('mine');

    fireEvent.click(screen.getByText('taskComments.edit'));
    fireEvent.change(screen.getByLabelText('taskComments.edit'), { target: { value: ' better ' } });
    fireEvent.click(screen.getByText('taskComments.save'));

    await waitFor(() => expect(api.editTaskComment).toHaveBeenCalledWith(7, 1, 'better'));
  });

  test('an edit can be abandoned, and a failed one is a toast', async () => {
    api.fetchTaskComments.mockResolvedValue(page([comment(1, 1, 'mine')]));
    api.editTaskComment.mockRejectedValue(new Error('403'));

    render(<TaskComments taskId={7} />);
    await screen.findByText('mine');

    fireEvent.click(screen.getByText('taskComments.edit'));
    fireEvent.click(screen.getByText('taskComments.cancel'));
    expect(screen.getByText('mine')).toBeInTheDocument();

    fireEvent.click(screen.getByText('taskComments.edit'));
    fireEvent.click(screen.getByText('taskComments.save'));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('taskComments.editError'));
  });

  test('loads older comments by offset and drops any it already has', async () => {
    const first = Array.from({ length: 25 }, (_, i) => comment(100 - i, 2, `c${100 - i}`));
    api.fetchTaskComments
      .mockResolvedValueOnce(page(first, 27))
      .mockResolvedValueOnce(page([comment(76, 2, 'c76'), comment(75, 2, 'c75'), comment(74, 2, 'c74')], 27));

    render(<TaskComments taskId={7} />);
    await screen.findByText('c100');

    fireEvent.click(screen.getByText('taskComments.loadOlder'));

    expect(await screen.findByText('c74')).toBeInTheDocument();
    expect(api.fetchTaskComments).toHaveBeenLastCalledWith(7, { page: 1, size: 25 });
    expect(screen.getAllByText('c76')).toHaveLength(1);
    expect(screen.queryByText('taskComments.loadOlder')).not.toBeInTheDocument();
  });

  test('a failed older page is a toast and keeps what is shown', async () => {
    api.fetchTaskComments
      .mockResolvedValueOnce(page([comment(2, 2, 'kept')], 30))
      .mockRejectedValueOnce(new Error('500'));

    render(<TaskComments taskId={7} />);
    await screen.findByText('kept');

    fireEvent.click(screen.getByText('taskComments.loadOlder'));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('taskComments.loadError'));
    expect(screen.getByText('kept')).toBeInTheDocument();
  });

  test('a comment elsewhere on the board makes the open thread re-read', async () => {
    api.fetchTaskComments.mockResolvedValue(page([]));

    render(<TaskComments taskId={7} />);
    await screen.findByText('taskComments.empty');

    api.fetchTaskComments.mockResolvedValue(page([comment(9, 2, 'live')]));
    act(() => {
      window.dispatchEvent(new CustomEvent('task-comments-changed'));
    });

    expect(await screen.findByText('live')).toBeInTheDocument();
  });

  test('a thread that will not load says so', async () => {
    api.fetchTaskComments.mockRejectedValue(new Error('500'));

    render(<TaskComments taskId={7} />);

    expect(await screen.findByText('taskComments.loadError')).toBeInTheDocument();
  });
});
