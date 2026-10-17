import { addSubTask, deleteSubTask, fetchSubTask, fetchSubTasksByTaskId, toggleSubTaskCompletion, updateSubTask } from '../../services/subtaskApi';

global.fetch = jest.fn();

beforeEach(() => {
  fetch.mockClear();
});

test('fetchSubTasksByTaskId should return subtasks for a task', async () => {
  const mockSubTasks = [
    { id: 1, title: 'SubTask 1', completed: false },
    { id: 2, title: 'SubTask 2', completed: true }
  ];

  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockSubTasks
  });

  const result = await fetchSubTasksByTaskId('1');

  expect(fetch).toHaveBeenCalledWith('/api/subtasks/task/1');
  expect(result).toEqual(mockSubTasks);
});

test('toggleSubTaskCompletion should toggle subtask status', async () => {
  const mockSubTask = { id: 1, title: 'SubTask 1', completed: true };
  
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockSubTask
  });

  const result = await toggleSubTaskCompletion(1);

  expect(fetch).toHaveBeenCalledWith('/api/subtasks/1/change', {
    method: 'PATCH'
  });
  expect(result).toEqual(mockSubTask);
});

test('fetchSubTask should return a specific subtask', async () => {
  const mockSubTask = { id: 'sub1', title: 'SubTask 1', completed: false };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockSubTask
  });
  
  const result = await fetchSubTask('sub1');
  
  expect(fetch).toHaveBeenCalledWith('/api/subtasks/sub1');
  expect(result).toEqual(mockSubTask);
});

test('fetchSubTasksByTaskId should return subtasks for a specific task', async () => {
  const taskId = 'task1';
  const mockSubTasks = [
    { id: 'sub1', title: 'SubTask 1', completed: false },
    { id: 'sub2', title: 'SubTask 2', completed: true }
  ];
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockSubTasks
  });
  
  const result = await fetchSubTasksByTaskId(taskId);
  
  expect(fetch).toHaveBeenCalledWith('/api/subtasks/task/task1');
  expect(result).toEqual(mockSubTasks);
});

test('fetchSubTasksByTaskId should handle network errors', async () => {
  fetch.mockImplementationOnce(() => {
    throw new Error('Network error');
  });
    
  await expect(fetchSubTasksByTaskId('1')).rejects.toThrow('Network error');
});

test('addSubTask should create a new subtask', async () => {
  const taskId = 'task1';
  const title = 'New SubTask';
  const mockSubTask = { id: 'sub3', title: 'New SubTask', completed: false };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockSubTask
  });
  
  const result = await addSubTask(taskId, title);
  
  expect(fetch).toHaveBeenCalledWith('/api/subtasks', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({
      title,
      completed: false,
      task: {
        id: taskId
      }
    }),
  });
  expect(result).toEqual(mockSubTask);
});

test('updateSubTask should update a subtask with provided data', async () => {
  const subTaskId = 'sub1';
  const subTaskData = { title: 'Updated SubTask', completed: true };
  const mockSubTask = { id: subTaskId, ...subTaskData };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockSubTask
  });
  
  const result = await updateSubTask(subTaskId, subTaskData);
  
  expect(fetch).toHaveBeenCalledWith('/api/subtasks/sub1', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(subTaskData),
  });
  expect(result).toEqual(mockSubTask);
});

test('toggleSubTaskCompletion should toggle the completion state', async () => {
  const subTaskId = 'sub1';
  const mockSubTask = { id: subTaskId, title: 'SubTask 1', completed: true };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockSubTask
  });
  
  const result = await toggleSubTaskCompletion(subTaskId);
  
  expect(fetch).toHaveBeenCalledWith('/api/subtasks/sub1/change', {
    method: 'PATCH'
  });
  expect(result).toEqual(mockSubTask);
});

test('deleteSubTask should delete a subtask', async () => {
  const subTaskId = 'sub1';
    
  fetch.mockResolvedValueOnce({
    ok: true
  });
  
  const result = await deleteSubTask(subTaskId);
  
  expect(fetch).toHaveBeenCalledWith('/api/subtasks/sub1', {
    method: 'DELETE'
  });
  expect(result).toBe(true);
});

test('deleteSubTask should handle 404 responses', async () => {
  const subTaskId = 'sub1';
    
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 404
  });
  
  const result = await deleteSubTask(subTaskId);
  
  expect(fetch).toHaveBeenCalledWith('/api/subtasks/sub1', {
    method: 'DELETE'
  });
  expect(result).toBe(true);
});

test('deleteSubTask should throw on error responses other than 404', async () => {
  const subTaskId = 'sub1';
    
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 500
  });
  
  await expect(deleteSubTask(subTaskId)).rejects.toMatchObject({ status: 500 });
  
  expect(fetch).toHaveBeenCalledWith('/api/subtasks/sub1', {
    method: 'DELETE'
  });
});
