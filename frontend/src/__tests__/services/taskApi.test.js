import { ConcurrentModificationError } from '../../services/reorder';
import { WipLimitExceededError, addTask, assignUserToTask, deleteTask, fetchTask, fetchTasks, removeUserFromTask, updateTask, updateTaskColumn, updateTaskLabels, updateTaskName, updateTaskPosition, updateTaskRow } from '../../services/taskApi';

global.fetch = jest.fn();

beforeEach(() => {
  fetch.mockClear();
});

test('fetchTasks should return tasks when successful', async () => {
  const mockTasks = [
    { id: '1', title: 'Task 1', columnId: 'col1' },
    { id: '2', title: 'Task 2', columnId: 'col2' }
  ];

  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockTasks
  });

  const result = await fetchTasks();

  expect(fetch).toHaveBeenCalledWith('/api/tasks');
  expect(result).toEqual(mockTasks);
});

test('addTask should create a new task', async () => {
  const mockTask = { id: '3', title: 'New Task', columnId: 'col1' };
  
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockTask
  });

  const result = await addTask('New Task', 'col1');

  expect(fetch).toHaveBeenCalledWith('/api/tasks', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      title: 'New Task',
      column: {
        id: 'col1'
      },
      deadline: null
    }),
  });
  expect(result).toEqual(mockTask);
});

test('updateTaskName should update task title', async () => {
  const mockTask = { id: '1', title: 'Updated Task' };
  
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockTask
  });

  const result = await updateTaskName('1', 'Updated Task');

  expect(fetch).toHaveBeenCalledWith('/api/tasks/1', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ title: 'Updated Task' }),
  });
  expect(result).toEqual(mockTask);
});

test('assignUserToTask should refuse before the PUT when the user is at their limit', async () => {
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => ({ userId: 123, wipLimit: 3, assignedCount: 3, withinLimit: false })
  });

  const error = await assignUserToTask('1', '123').catch(e => e);

  expect(error).toBeInstanceOf(WipLimitExceededError);
  expect(error.status).toEqual({ userId: 123, wipLimit: 3, assignedCount: 3, withinLimit: false });
  expect(fetch).toHaveBeenCalledWith('/api/users/123/wip-status');
  expect(fetch).toHaveBeenCalledTimes(1); 
});

test('assignUserToTask should assign user when WIP limit not exceeded', async () => {
  const mockTask = { id: '1', title: 'Task', userIds: ['123'] };
  
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => ({ userId: 123, wipLimit: 5, assignedCount: 1, withinLimit: true })
  });
  
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockTask
  });

  const result = await assignUserToTask('1', '123');

  expect(fetch).toHaveBeenCalledTimes(2);
  expect(fetch).toHaveBeenNthCalledWith(1, '/api/users/123/wip-status');
  expect(fetch).toHaveBeenNthCalledWith(2, '/api/tasks/1/user/123', {
    method: 'PUT'
  });
  expect(result).toEqual(mockTask);
});

test('fetchTask should return a specific task', async () => {
  const mockTask = { id: '1', title: 'Task 1', columnId: 'col1' };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockTask
  });
  
  const result = await fetchTask('1');
  
  expect(fetch).toHaveBeenCalledWith('/api/tasks/1');
  expect(result).toEqual(mockTask);
});

test('updateTask should update a task with provided data', async () => {
  const mockTask = { id: '1', title: 'Updated Task', description: 'New description' };
  const taskData = { title: 'Updated Task', description: 'New description' };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockTask
  });
  
  const result = await updateTask('1', taskData);
  
  expect(fetch).toHaveBeenCalledWith('/api/tasks/1', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(taskData),
  });
  expect(result).toEqual(mockTask);
});

test('updateTaskColumn should update task column', async () => {
  const mockTask = { id: '1', title: 'Task 1', column: { id: 'col2' } };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockTask
  });
  
  const result = await updateTaskColumn('1', 'col2');
  
  expect(fetch).toHaveBeenCalledWith('/api/tasks/1', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      column: {
        id: 'col2'
      }
    }),
  });
  expect(result).toEqual(mockTask);
});

test('updateTaskColumn should handle error responses with details', async () => {
  const errorResponse = { message: 'Column WIP limit exceeded' };
    
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400,
    json: async () => errorResponse
  });
  
  await expect(updateTaskColumn('1', 'col2')).rejects.toMatchObject({ status: 400 });
  
  expect(fetch).toHaveBeenCalledWith('/api/tasks/1', expect.any(Object));
});

test('updateTaskPosition should update task position', async () => {
  const mockTask = { id: '1', title: 'Task 1', position: 3 };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockTask
  });
  
  const result = await updateTaskPosition('1', 3);
  
  expect(fetch).toHaveBeenCalledWith('/api/tasks/1/position/3', {
    method: 'PATCH'
  });
  expect(result).toEqual(mockTask);
});

test('removeUserFromTask should remove user from task', async () => {
  const mockTask = { id: '1', title: 'Task 1', users: [] };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockTask
  });
  
  const result = await removeUserFromTask('1', '123');
  
  expect(fetch).toHaveBeenCalledWith('/api/tasks/1/user/123', {
    method: 'DELETE'
  });
  expect(result).toEqual(mockTask);
});

test('removeUserFromTask should handle network errors', async () => {
  fetch.mockImplementationOnce(() => {
    throw new Error('Network error');
  });
    
  await expect(removeUserFromTask('1', 'user1')).rejects.toThrow('Network error');
});

test('updateTaskLabels should update all task labels', async () => {
  const mockTask = { id: '1', title: 'Task 1', labels: ['bug', 'critical'] };
  const labels = ['bug', 'critical'];
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockTask
  });
  
  const result = await updateTaskLabels('1', labels);
  
  expect(fetch).toHaveBeenCalledWith('/api/tasks/1/labels', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(labels)
  });
  expect(result).toEqual(mockTask);
});

test('updateTaskLabels should handle error responses', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400,
    text: async () => 'Bad request',
    json: async () => ({ message: 'Bad request' })
  });
    
  await expect(updateTaskLabels('1', ['bug', 'feature'])).rejects.toMatchObject({ status: 400 });
});

test('deleteTask should delete a task', async () => {
  fetch.mockResolvedValueOnce({
    ok: true
  });
  
  const result = await deleteTask('1');
  
  expect(fetch).toHaveBeenCalledWith('/api/tasks/1', {
    method: 'DELETE'
  });
  expect(result).toBe(true);
});

test('deleteTask should handle 404 responses', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 404
  });
  
  const result = await deleteTask('1');
  
  expect(fetch).toHaveBeenCalledWith('/api/tasks/1', {
    method: 'DELETE'
  });
  expect(result).toBe(true);
});

test('updateTaskRow should update task row', async () => {
  const mockTask = { id: '1', title: 'Task 1', row: { id: 'row2' } };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockTask
  });
  
  const result = await updateTaskRow('1', 'row2');
  
  expect(fetch).toHaveBeenCalledWith('/api/tasks/1', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      row: {
        id: 'row2'
      }
    }),
  });
  expect(result).toEqual(mockTask);
});

test('updateTaskRow should handle null row ID', async () => {
  const mockTask = { id: '1', title: 'Task 1', row: null };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockTask
  });
  
  const result = await updateTaskRow('1', null);
  
  expect(fetch).toHaveBeenCalledWith('/api/tasks/1', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      row: null
    }),
  });
  expect(result).toEqual(mockTask);
});

test('updateTaskRow should handle error responses with details', async () => {
  const errorResponse = { message: 'Row not found' };
    
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 404,
    json: async () => errorResponse
  });
  
  await expect(updateTaskRow('1', 'row2')).rejects.toMatchObject({ status: 404 });
  
  expect(fetch).toHaveBeenCalledWith('/api/tasks/1', expect.any(Object));
});

test('updateTask should handle error responses', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400
  });

  await expect(updateTask('task1', { title: 'Updated' })).rejects.toMatchObject({ status: 400 });
});

test('updateTask should raise ConcurrentModificationError on a 409', async () => {
  fetch.mockResolvedValue({ ok: false, status: 409 });

  await expect(updateTask('1', { title: 'x', version: 2 }))
    .rejects.toBeInstanceOf(ConcurrentModificationError);
  await expect(updateTask('1', { title: 'x', version: 2 }))
    .rejects.toThrow('changed by someone else');
});

test('updateTaskName should handle error responses', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400
  });
    
  await expect(updateTaskName('task1', 'New Name')).rejects.toMatchObject({ status: 400 });
});

test('fetchTask should handle error responses', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 404
  });
    
  await expect(fetchTask('task1')).rejects.toMatchObject({ status: 404 });
});

test('addTask should handle error responses', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400
  });
    
  await expect(addTask('Task', 'col1')).rejects.toMatchObject({ status: 400 });
});

test('updateTaskRow should handle error with no error data', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 500,
    json: async () => { throw new Error('Invalid JSON'); }
  });
    
  await expect(updateTaskRow('task1', 'row1')).rejects.toMatchObject({ status: 500 });
});

test('updateTaskColumn should handle error with no error data', async () => {
    fetch.mockResolvedValueOnce({
      ok: false,
      status: 500,
      json: async () => { throw new Error('Invalid JSON'); }
    });
    
    await expect(updateTaskColumn('task1', 'col1')).rejects.toMatchObject({ status: 500 });
});

test('assignUserToTask reports a network failure as itself, not as a WIP limit', async () => {
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => ({ userId: 1, wipLimit: 5, assignedCount: 1, withinLimit: true })
  });
    
  fetch.mockImplementationOnce(() => {
    throw new Error('Network error');
  });
    
  const error = await assignUserToTask('task1', 'user1').catch(e => e);

  expect(error).not.toBeInstanceOf(WipLimitExceededError);
  expect(error.message).toBe('Network error');
});

test('assignUserToTask reports a 404 as itself, not as a WIP limit', async () => {
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => ({ userId: 1, wipLimit: 5, assignedCount: 1, withinLimit: true })
  });
    
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 404,
    json: async () => ({ code: 'TASK_NOT_FOUND', message: 'Task not found' })
  });
    
  const error = await assignUserToTask('task1', 'user1').catch(e => e);

  expect(error).not.toBeInstanceOf(WipLimitExceededError);
  expect(error).toMatchObject({ status: 404, code: 'TASK_NOT_FOUND', message: 'Task not found' });
});

test('assignUserToTask maps the server WIP refusal onto WipLimitExceededError', async () => {
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => ({ userId: 1, wipLimit: 3, assignedCount: 2, withinLimit: true })
  });
    
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400,
    json: async () => ({
      code: 'USER_WIP_LIMIT_EXCEEDED',
      message: "The user's WIP limit has been exceeded"
    })
  });
    
  const error = await assignUserToTask('task1', 'user1').catch(e => e);

  expect(error).toBeInstanceOf(WipLimitExceededError);
  expect(fetch).toHaveBeenCalledTimes(2);
});

test('updateTaskPosition should handle error responses', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400
  });
    
  await expect(updateTaskPosition('1', 2)).rejects.toMatchObject({ status: 400 });
});

test('updateTaskLabels rejects with the status of an error response', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400,
    json: async () => ({ message: 'Bad request details' })
  });
    
  await expect(updateTaskLabels('1', [])).rejects.toMatchObject({ status: 400 });
});

test('updateTaskLabels rejects with the status when the error body is not JSON', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400,
    json: async () => { throw new Error('Invalid JSON'); }
  });
    
  await expect(updateTaskLabels('1', [])).rejects.toMatchObject({ status: 400 });
});
