import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor, act } from '@testing-library/react';
import { toast } from 'react-toastify';
import { useTaskComments } from '../../components/comments/useTaskComments';
import * as api from '../../services/api';
import { createQueryClient } from '../../services/queryClient';

jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: key => key }) }));
jest.mock('react-toastify', () => ({ toast: { error: jest.fn(), success: jest.fn() } }));
jest.mock('../../services/api');

const comments = (from, to) => {
  const list = [];
  for (let id = from; id >= to; id--) {
    list.push({ id, taskId: 7, body: `c${id}`, authorId: 1, createdAt: '2026-09-22T10:00:00Z' });
  }
  return list;
};
const answer = (list, totalEntries) => ({ comments: list, totalEntries });

const setup = (taskId = 7) => {
  const client = createQueryClient();
  const wrapper = ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const hook = renderHook(({ id }) => useTaskComments(id), { wrapper, initialProps: { id: taskId } });
  return { client, ...hook };
};

beforeEach(() => {
  jest.resetAllMocks();
  console.error = jest.fn();
});

test('a re-read after older pages were loaded asks for as many comments as are shown', async () => {
  api.fetchTaskComments
    .mockResolvedValueOnce(answer(comments(60, 36), 60))
    .mockResolvedValueOnce(answer(comments(35, 11), 60));
  const { result, client } = setup();
  await waitFor(() => expect(result.current.comments).toHaveLength(25));
  await act(() => result.current.loadOlder());
  expect(result.current.comments).toHaveLength(50);

  api.fetchTaskComments.mockResolvedValue(answer(comments(61, 12), 61));
  await act(() => client.invalidateQueries({ queryKey: ['comments', 7] }));

  expect(api.fetchTaskComments).toHaveBeenLastCalledWith(7, { page: 0, size: 50 });
});

test('a re-read never asks for more than 100 comments', async () => {
  api.fetchTaskComments.mockResolvedValueOnce(answer(comments(200, 76), 200));
  const { result, client } = setup();
  await waitFor(() => expect(result.current.comments).toHaveLength(125));
  api.fetchTaskComments.mockResolvedValue(answer(comments(200, 101), 200));

  await act(() => client.invalidateQueries({ queryKey: ['comments', 7] }));

  expect(api.fetchTaskComments).toHaveBeenLastCalledWith(7, { page: 0, size: 100 });
});

test('loading older comments appends the new ones, skips repeats and takes the new total', async () => {
  api.fetchTaskComments
    .mockResolvedValueOnce(answer(comments(30, 6), 30))
    .mockResolvedValueOnce(answer(comments(7, 1), 31));
  const { result } = setup();
  await waitFor(() => expect(result.current.comments).toHaveLength(25));

  await act(() => result.current.loadOlder());

  expect(api.fetchTaskComments).toHaveBeenLastCalledWith(7, { page: 1, size: 25 });
  expect(result.current.comments.map(c => c.id)).toEqual([...comments(30, 6), ...comments(5, 1)].map(c => c.id));
  expect(result.current.total).toBe(31);
});

test('a failed re-read keeps the comments on screen and reports the failure', async () => {
  api.fetchTaskComments.mockResolvedValueOnce(answer(comments(3, 1), 3));
  const { result, client } = setup();
  await waitFor(() => expect(result.current.comments).toHaveLength(3));
  api.fetchTaskComments.mockRejectedValue(new Error('down'));

  await act(() => client.invalidateQueries({ queryKey: ['comments', 7] }));

  await waitFor(() => expect(result.current.failed).toBe(true));
  expect(result.current.comments).toHaveLength(3);
});

test('a failed older page toasts and leaves the total alone', async () => {
  api.fetchTaskComments.mockResolvedValueOnce(answer(comments(30, 6), 30));
  const { result } = setup();
  await waitFor(() => expect(result.current.comments).toHaveLength(25));
  api.fetchTaskComments.mockRejectedValue(new Error('down'));

  await act(() => result.current.loadOlder());

  expect(toast.error).toHaveBeenCalledWith('taskComments.loadError');
  expect(result.current.total).toBe(30);
  expect(result.current.failed).toBe(false);
});

test('an empty answer reads as an empty thread', async () => {
  api.fetchTaskComments.mockResolvedValue(null);
  const { result } = setup();

  await waitFor(() => expect(result.current.loading).toBe(false));

  expect(result.current.comments).toEqual([]);
  expect(result.current.total).toBe(0);
});

test('two tasks do not share a thread', async () => {
  api.fetchTaskComments.mockImplementation(async id => answer([{ id: id * 10, body: `of ${id}` }], 1));
  const { result, rerender } = setup(7);
  await waitFor(() => expect(result.current.comments[0]?.body).toBe('of 7'));

  rerender({ id: 8 });

  await waitFor(() => expect(result.current.comments[0]?.body).toBe('of 8'));
});

test('posting, editing and deleting each re-read the thread', async () => {
  api.fetchTaskComments.mockResolvedValue(answer([], 0));
  api.addTaskComment.mockResolvedValue({});
  api.editTaskComment.mockResolvedValue({});
  api.deleteTaskComment.mockResolvedValue({});
  const { result } = setup();
  await waitFor(() => expect(result.current.loading).toBe(false));
  const reads = () => api.fetchTaskComments.mock.calls.length;

  await act(() => result.current.post('hi'));
  expect(reads()).toBe(2);
  await act(() => result.current.edit(1, 'ho'));
  expect(reads()).toBe(3);
  await act(() => result.current.remove(1));
  expect(reads()).toBe(4);
});

test('a failed edit toasts, returns false and does not re-read', async () => {
  api.fetchTaskComments.mockResolvedValue(answer([], 0));
  api.editTaskComment.mockRejectedValue(new Error('no'));
  const { result } = setup();
  await waitFor(() => expect(result.current.loading).toBe(false));

  let ok;
  await act(async () => { ok = await result.current.edit(1, 'ho'); });

  expect(ok).toBe(false);
  expect(toast.error).toHaveBeenCalledWith('taskComments.editError');
  expect(api.fetchTaskComments).toHaveBeenCalledTimes(1);
});

test('an unusable draft is refused without calling the API', async () => {
  api.fetchTaskComments.mockResolvedValue(answer([], 0));
  const { result } = setup();
  await waitFor(() => expect(result.current.loading).toBe(false));

  let results;
  await act(async () => {
    results = [
      await result.current.post('   '),
      await result.current.post('x'.repeat(2001)),
      await result.current.edit(1, '')
    ];
  });

  expect(results).toEqual([false, false, false]);
  expect(api.addTaskComment).not.toHaveBeenCalled();
  expect(api.editTaskComment).not.toHaveBeenCalled();
});
