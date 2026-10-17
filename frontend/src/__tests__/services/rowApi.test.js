import { addRow, deleteRow, fetchRows, updateRowName, updateRowPosition, updateRowWipLimit } from '../../services/rowApi';

global.fetch = jest.fn();

beforeEach(() => {
  fetch.mockClear();
});

test('updateRowName should update row name', async () => {
  const mockRow = { id: 'row1', name: 'Updated Row' };
        
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockRow
  });

  const result = await updateRowName('row1', 'Updated Row');

  expect(fetch).toHaveBeenCalledWith('/api/rows/row1', {
    method: 'PATCH',
    headers: {
        'Content-Type': 'application/json',
    },
    body: JSON.stringify({ name: 'Updated Row' }),
    });
  expect(result).toEqual(mockRow);
});

test('updateRowWipLimit should update row WIP limit', async () => {
    const mockRow = { id: 'row1', wipLimit: 3 };
    const wipLimit = 3;
            
    fetch.mockResolvedValueOnce({
        ok: true,
        status: 200,
        json: async () => mockRow
    });
        
    const result = await updateRowWipLimit('row1', wipLimit);
        
    expect(fetch).toHaveBeenCalledWith('/api/rows/row1', {
        method: 'PATCH',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({
            wipLimit: parseInt(wipLimit)
        }),
    });
    expect(result).toEqual(mockRow);
});

test('fetchRows should retry on failure', async () => {
    const mockRows = [{ id: 'row1', name: 'Features' }];
        
    fetch.mockResolvedValueOnce({
        ok: false,
        status: 500
    });
        
    fetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockRows
    });

    const result = await fetchRows(2);

    expect(fetch).toHaveBeenCalledTimes(2);
    expect(result).toEqual(mockRows);
});

test('addRow should create a new row', async () => {
    const mockRow = { id: 'row1', name: 'New Row', wipLimit: 5 };
        
    fetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockRow
    });

    const result = await addRow('New Row', 5);

    expect(fetch).toHaveBeenCalledWith('/api/rows', {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({ name: 'New Row', wipLimit: 5 }),
    });
    expect(result).toEqual(mockRow);
});

test('updateRowPosition should update row position', async () => {
    const mockRow = { id: 'row1', position: 2 };
        
    fetch.mockResolvedValueOnce({
        ok: true,
        json: async () => mockRow
    });

    const result = await updateRowPosition('row1', 2);

    expect(fetch).toHaveBeenCalledWith('/api/rows/row1/position/2', {
        method: 'PATCH'
    });
    expect(result).toEqual(mockRow);
});

test('deleteRow should delete a row', async () => {
    fetch.mockResolvedValueOnce({
        ok: true
    });

    const result = await deleteRow('row1');

    expect(fetch).toHaveBeenCalledWith('/api/rows/row1', {
        method: 'DELETE'
    });
    expect(result).toBe(true);
});

test('fetchRows should return rows when successful', async () => {
    const mockRows = [
      { id: 'row1', name: 'Team A', position: 0, wipLimit: 5 },
      { id: 'row2', name: 'Team B', position: 1, wipLimit: 3 }
    ];
  
    fetch.mockResolvedValueOnce({
      ok: true,
      json: async () => mockRows
    });
  
    const result = await fetchRows();
    expect(fetch).toHaveBeenCalledWith('/api/rows');
    expect(result).toEqual(mockRows);
});

test('fetchRows should throw after all retries fail', async () => {
    fetch.mockResolvedValue({
      ok: false,
      status: 500
    });
  
    await expect(fetchRows(2)).rejects.toMatchObject({ status: 500 });
    expect(fetch).toHaveBeenCalledTimes(2);
});

test('deleteRow should handle 404 responses', async () => {
    fetch.mockResolvedValueOnce({
      ok: false,
      status: 404
    });
  
    const result = await deleteRow('row1');
  
    expect(fetch).toHaveBeenCalledWith('/api/rows/row1', {
      method: 'DELETE'
    });
    expect(result).toBe(true);
});

test('updateRowName should throw on error', async () => {
    fetch.mockResolvedValueOnce({
      ok: false,
      status: 500
    });
  
    await expect(updateRowName('row1', 'Updated Team Name')).rejects.toMatchObject({ status: 500 });
  
    expect(fetch).toHaveBeenCalledWith('/api/rows/row1', expect.any(Object));
});

test('addRow should handle error responses', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400
  });
    
  await expect(addRow('Row', 5)).rejects.toMatchObject({ status: 400 });
});

test('deleteRow should handle error not related to 404', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 500
  });
    
  await expect(deleteRow('row1')).rejects.toMatchObject({ status: 500 });
});

test('fetchRows should throw error on fetch failure', async () => {
  fetch.mockImplementationOnce(() => {
    throw new Error('Network error');
  });
    
  await expect(fetchRows(1)).rejects.toThrow('Network error');
});
