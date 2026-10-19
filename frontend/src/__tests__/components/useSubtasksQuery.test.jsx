import React from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { renderHook, waitFor, act } from '@testing-library/react';
import { toast } from 'react-toastify';
import { useSubtasks } from '../../components/taskDetails/subtasks/useSubtasks';
import * as api from '../../services/api';
import { createQueryClient } from '../../services/queryClient';

jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: key => key }) }));
jest.mock('react-toastify', () => ({ toast: { error: jest.fn(), success: jest.fn() } }));
jest.mock('../../services/api');

const list = () => [
  { id: 1, title: 'one', completed: false, description: 'a' },
  { id: 2, title: 'two', completed: false, description: 'b' }
];

const setup = async (taskId = 7) => {
  const client = createQueryClient();
  const onSubtaskUpdate = jest.fn();
  const onSaved = jest.fn();
  const wrapper = ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const hook = renderHook(() => useSubtasks(taskId, onSubtaskUpdate, onSaved), { wrapper });
  await waitFor(() => expect(hook.result.current.loaded).toBe(true));
  return { client, onSubtaskUpdate, onSaved, ...hook };
};

beforeEach(() => {
  jest.resetAllMocks();
  console.error = jest.fn();
  api.fetchSubTasksByTaskId.mockResolvedValue(list());
});

test('a null answer reads as no subtasks', async () => {
  api.fetchSubTasksByTaskId.mockResolvedValue(null);

  const { result } = await setup();

  expect(result.current.subtasks).toEqual([]);
});

test('toggling flips only the matching subtask, without re-reading, and tells the parent', async () => {
  api.toggleSubTaskCompletion.mockResolvedValue({});
  const { result, onSubtaskUpdate } = await setup();

  await act(() => result.current.toggle(2));

  expect(result.current.subtasks.map(s => s.completed)).toEqual([false, true]);
  expect(api.fetchSubTasksByTaskId).toHaveBeenCalledTimes(1);
  expect(onSubtaskUpdate).toHaveBeenCalledTimes(1);
});

test('a failed toggle leaves the cache alone, toasts and tells nobody', async () => {
  api.toggleSubTaskCompletion.mockRejectedValue(new Error('no'));
  const { result, client, onSubtaskUpdate } = await setup();

  await act(() => result.current.toggle(1));

  expect(client.getQueryData(['subtasks', 7])).toEqual(list());
  expect(toast.error).toHaveBeenCalledWith('notifications.subtaskToggleError');
  expect(onSubtaskUpdate).not.toHaveBeenCalled();
});

test('adding re-reads, then tells the parent and reports saved', async () => {
  api.addSubTask.mockResolvedValue({});
  const { result, onSubtaskUpdate, onSaved } = await setup();
  api.fetchSubTasksByTaskId.mockResolvedValue([...list(), { id: 3, title: 'three', completed: false }]);

  let ok;
  await act(async () => { ok = await result.current.add('three'); });

  expect(ok).toBe(true);
  expect(api.addSubTask).toHaveBeenCalledWith(7, 'three');
  expect(result.current.subtasks).toHaveLength(3);
  expect(onSubtaskUpdate).toHaveBeenCalledTimes(1);
  expect(onSaved).toHaveBeenCalledTimes(1);
});

test('a failed add toasts, returns false and does not re-read', async () => {
  api.addSubTask.mockRejectedValue(new Error('no'));
  const { result, onSubtaskUpdate, onSaved } = await setup();

  let ok;
  await act(async () => { ok = await result.current.add('three'); });

  expect(ok).toBe(false);
  expect(toast.error).toHaveBeenCalledWith('notifications.subtaskAddError');
  expect(api.fetchSubTasksByTaskId).toHaveBeenCalledTimes(1);
  expect(onSubtaskUpdate).not.toHaveBeenCalled();
  expect(onSaved).not.toHaveBeenCalled();
});

test('removing drops only that subtask and reports saved', async () => {
  api.deleteSubTask.mockResolvedValue({});
  const { result, onSubtaskUpdate, onSaved } = await setup();

  await act(() => result.current.remove(1));

  expect(result.current.subtasks.map(s => s.id)).toEqual([2]);
  expect(onSubtaskUpdate).toHaveBeenCalledTimes(1);
  expect(onSaved).toHaveBeenCalledTimes(1);
});

test('a failed removal keeps the subtask and toasts', async () => {
  api.deleteSubTask.mockRejectedValue(new Error('no'));
  const { result, onSaved } = await setup();

  await act(() => result.current.remove(1));

  expect(result.current.subtasks).toHaveLength(2);
  expect(toast.error).toHaveBeenCalledWith('notifications.subtaskDeleteError');
  expect(onSaved).not.toHaveBeenCalled();
});

test('saving a description changes only that subtask and does not touch the parent count', async () => {
  api.updateSubTask.mockResolvedValue({});
  const { result, onSubtaskUpdate, onSaved } = await setup();

  let ok;
  await act(async () => { ok = await result.current.saveDescription(2, 'new'); });

  expect(ok).toBe(true);
  expect(api.updateSubTask).toHaveBeenCalledWith(2, { description: 'new' });
  expect(result.current.subtasks.map(s => s.description)).toEqual(['a', 'new']);
  expect(onSaved).toHaveBeenCalledTimes(1);
  expect(onSubtaskUpdate).not.toHaveBeenCalled();
});

test('a failed description save keeps the old text and returns false', async () => {
  api.updateSubTask.mockRejectedValue(new Error('no'));
  const { result, onSaved } = await setup();

  let ok;
  await act(async () => { ok = await result.current.saveDescription(2, 'new'); });

  expect(ok).toBe(false);
  expect(result.current.subtasks.map(s => s.description)).toEqual(['a', 'b']);
  expect(toast.error).toHaveBeenCalledWith('notifications.subtaskDescriptionError');
  expect(onSaved).not.toHaveBeenCalled();
});

test('the parent callback is optional', async () => {
  api.toggleSubTaskCompletion.mockResolvedValue({});
  const client = createQueryClient();
  const wrapper = ({ children }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  const { result } = renderHook(() => useSubtasks(7, undefined, jest.fn()), { wrapper });
  await waitFor(() => expect(result.current.loaded).toBe(true));

  await act(() => result.current.toggle(1));

  expect(result.current.subtasks[0].completed).toBe(true);
  expect(toast.error).not.toHaveBeenCalled();
});
