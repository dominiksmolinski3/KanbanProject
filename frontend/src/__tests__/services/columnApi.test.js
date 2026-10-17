import { addColumn, deleteColumn, fetchColumns, updateColumnName, updateColumnPosition, updateColumnWipLimit } from '../../services/columnApi';

global.fetch = jest.fn();

beforeEach(() => {
  fetch.mockClear();
});

test('fetchColumns should return columns when successful', async () => {
  const mockColumns = [
    { id: 'col1', name: 'To Do', position: 0 },
    { id: 'col2', name: 'In Progress', position: 1 }
  ];

  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockColumns
  });

  const result = await fetchColumns();
  expect(fetch).toHaveBeenCalledWith('/api/columns');
  expect(result).toEqual(mockColumns);
});

test('fetchColumns should retry on failure', async () => {
  const mockColumns = [{ id: 'col1', name: 'To Do' }];
  
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 500
  });
  
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 500
  });
  
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockColumns
  });

  const result = await fetchColumns();
  expect(fetch).toHaveBeenCalledTimes(3);
  expect(result).toEqual(mockColumns);
});

test('fetchColumns should throw after all retries fail', async () => {
  fetch.mockResolvedValue({
    ok: false,
    status: 500
  });

  await expect(fetchColumns(2)).rejects.toMatchObject({ status: 500 });
  expect(fetch).toHaveBeenCalledTimes(2);
});

test('fetchColumns should throw error on fetch failure', async () => {
  fetch.mockImplementationOnce(() => {
    throw new Error('Network error');
  });
    
  await expect(fetchColumns(1)).rejects.toThrow('Network error');
});

test('updateColumnName should update column name', async () => {
  const mockColumn = { id: 'col1', name: 'Updated Name' };
  
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockColumn
  });

  const result = await updateColumnName('col1', 'Updated Name');

  expect(fetch).toHaveBeenCalledWith('/api/columns/col1', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ name: 'Updated Name' }),
  });
  expect(result).toEqual(mockColumn);
});

test('addColumn should create a new column', async () => {
  const mockColumn = { id: 'col3', name: 'New Column', wipLimit: 5 };
  
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockColumn
  });

  const result = await addColumn('New Column', 5);

  expect(fetch).toHaveBeenCalledWith('/api/columns', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ name: 'New Column', wipLimit: 5 }),
  });
  expect(result).toEqual(mockColumn);
});

test('updateColumnPosition should update column position', async () => {
    const mockColumn = { id: 'col1', position: 2 };
    
    fetch.mockResolvedValueOnce({
      ok: true,
      json: async () => mockColumn
    });
  
    const result = await updateColumnPosition('col1', 2);
  
    expect(fetch).toHaveBeenCalledWith('/api/columns/col1/position/2', {
      method: 'PATCH'
    });
    expect(result).toEqual(mockColumn);
});

test('deleteColumn should delete a column', async () => {
  fetch.mockResolvedValueOnce({
    ok: true
  });
  
  const result = await deleteColumn('col1');
  
  expect(fetch).toHaveBeenCalledWith('/api/columns/col1', {
    method: 'DELETE'
  });
  expect(result).toBe(true);
});

test('deleteColumn should handle 404 responses', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 404
  });
  
  const result = await deleteColumn('col1');
  
  expect(fetch).toHaveBeenCalledWith('/api/columns/col1', {
    method: 'DELETE'
  });
  expect(result).toBe(true);
});

test('deleteColumn should throw on error responses other than 404', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 500
  });
  
  await expect(deleteColumn('col1')).rejects.toMatchObject({ status: 500 });
  
  expect(fetch).toHaveBeenCalledWith('/api/columns/col1', {
    method: 'DELETE'
  });
});

test('addColumn should handle error responses', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400
  });
    
  await expect(addColumn('Column', 5)).rejects.toMatchObject({ status: 400 });
});

test('updateColumnWipLimit should handle error responses', async () => {
  fetch.mockResolvedValueOnce({
    status: 400
  });
    
  await expect(updateColumnWipLimit('col1', 5)).rejects.toMatchObject({ status: 400 });
});

test('updateColumnName should handle error responses', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400
  });
    
  await expect(updateColumnName('col1', 'New Name')).rejects.toMatchObject({ status: 400 });
});

test('fetchColumns gives up after the requested number of attempts', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 500
  });
  
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 500
  });
  
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 500
  });
  
  await expect(fetchColumns(3)).rejects.toMatchObject({ status: 500 });
  expect(fetch).toHaveBeenCalledTimes(3);
});
