import {
  assignParentTask,
  canTaskBeCompleted,
  getChildTasks,
  removeParentTask,
} from '../../services/taskHierarchyApi';

global.fetch = jest.fn();

beforeEach(() => {
  fetch.mockReset();
  fetch.mockResolvedValue({ ok: true, json: async () => ({ id: 2 }) });
});

test('assignParentTask puts the child under the parent', async () => {
  await expect(assignParentTask(2, 1)).resolves.toEqual({ id: 2 });

  expect(fetch).toHaveBeenCalledWith('/api/tasks/2/parent/1', { method: 'PUT' });
});

test('removeParentTask deletes the link and reads the task back', async () => {
  await expect(removeParentTask(2)).resolves.toEqual({ id: 2 });

  expect(fetch).toHaveBeenCalledWith('/api/tasks/2/parent', { method: 'DELETE' });
});

test('getChildTasks reads the children', async () => {
  fetch.mockResolvedValueOnce({ ok: true, json: async () => [{ id: 3 }] });

  await expect(getChildTasks(2)).resolves.toEqual([{ id: 3 }]);

  expect(fetch).toHaveBeenCalledWith('/api/tasks/2/children');
});

test('canTaskBeCompleted reads a plain boolean', async () => {
  fetch.mockResolvedValueOnce({ ok: true, json: async () => false });

  await expect(canTaskBeCompleted(2)).resolves.toBe(false);

  expect(fetch).toHaveBeenCalledWith('/api/tasks/2/can-complete');
});

test('each of them refuses a failed answer with an API error', async () => {
  fetch.mockResolvedValue({ ok: false, status: 404, json: async () => ({}) });

  await expect(assignParentTask(2, 1)).rejects.toMatchObject({ status: 404 });
  await expect(removeParentTask(2)).rejects.toMatchObject({ status: 404 });
  await expect(getChildTasks(2)).rejects.toMatchObject({ status: 404 });
  await expect(canTaskBeCompleted(2)).rejects.toMatchObject({ status: 404 });
});
