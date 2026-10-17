import { deleteUser, fetchUser, fetchUsers, getUserAvatar, getUserWipStatus, updateUserWipLimit } from '../../services/userApi';

global.fetch = jest.fn();
URL.createObjectURL = jest.fn().mockReturnValue('mock-blob-url');

beforeEach(() => {
  fetch.mockClear();
  URL.createObjectURL.mockClear();
});

test('updateUserWipLimit should update user WIP limit', async () => {
  const mockUser = { id: '123', name: 'Test User', wipLimit: 5 };
  
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockUser
  });

  const result = await updateUserWipLimit('123', 5);

  expect(fetch).toHaveBeenCalledWith('/api/users/123/wip-limit', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(5),
  });
  expect(result).toEqual(mockUser);
});

test('getUserAvatar should return blob URL when successful', async () => {
  const mockBlob = new Blob(['mock image data'], { type: 'image/jpeg' });
  
  fetch.mockResolvedValueOnce({
    ok: true,
    blob: async () => mockBlob
  });

  const result = await getUserAvatar('123');

  expect(fetch).toHaveBeenCalledWith('/api/users/123/avatar', {
    headers: {
      'Accept': 'image/*, application/json',
      'Cache-Control': 'no-cache'
    }
  });
  expect(URL.createObjectURL).toHaveBeenCalledWith(mockBlob);
  expect(result).toBe('mock-blob-url');
});

test('getUserAvatar should return null when fetch fails', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 404
  });

  const result = await getUserAvatar('123');

  expect(fetch).toHaveBeenCalled();
  expect(result).toBeNull();
});

test('fetchUsers should return users when successful', async () => {
  const mockUsers = [
    { id: 'user1', name: 'John Doe', email: 'john@example.com' },
    { id: 'user2', name: 'Jane Smith', email: 'jane@example.com' }
  ];
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockUsers
  });
  
  const result = await fetchUsers();
  
  expect(fetch).toHaveBeenCalledWith('/api/users');
  expect(result).toEqual(mockUsers);
});

test('fetchUser should return a specific user', async () => {
  const mockUser = { id: 'user1', name: 'John Doe', email: 'john@example.com' };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockUser
  });
  
  const result = await fetchUser('user1');
  
  expect(fetch).toHaveBeenCalledWith('/api/users/user1');
  expect(result).toEqual(mockUser);
});

test('deleteUser should delete a user', async () => {
  fetch.mockResolvedValueOnce({
    ok: true
  });
  
  const result = await deleteUser('user1');
  
  expect(fetch).toHaveBeenCalledWith('/api/users/user1', {
    method: 'DELETE'
  });
  expect(result).toBe(true);
});

test('deleteUser should handle 404 responses', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 404
  });
  
  const result = await deleteUser('user1');
  
  expect(fetch).toHaveBeenCalledWith('/api/users/user1', {
    method: 'DELETE'
  });
  expect(result).toBe(true);
});

test('updateUserWipLimit should handle non-numeric input', async () => {
  const mockUser = { id: 'user1', wipLimit: 5 };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockUser
  });
    
  const result = await updateUserWipLimit('user1', '5');
    
  expect(fetch).toHaveBeenCalledWith('/api/users/user1/wip-limit', {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(5),
  });
  expect(result).toEqual(mockUser);
});

test('getUserWipStatus should return the WIP status record', async () => {
  const userId = 'user1';
  const mockStatus = { 
    userId: 1,
    wipLimit: 5,
    assignedCount: 3,
    withinLimit: true
  };
    
  fetch.mockResolvedValueOnce({
    ok: true,
    json: async () => mockStatus
  });
  
  const result = await getUserWipStatus(userId);
  
  expect(fetch).toHaveBeenCalledWith('/api/users/user1/wip-status');
  expect(result).toEqual(mockStatus);
});

test('updateUserWipLimit should handle error response', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400
  });
    
  await expect(updateUserWipLimit('user1', 5)).rejects.toMatchObject({ status: 400 });
});

test('getUserWipStatus should handle response with status 400', async () => {
  fetch.mockResolvedValueOnce({
    ok: false,
    status: 400
  });
    
  await expect(getUserWipStatus('user1')).rejects.toMatchObject({ status: 400 });
});

test('getUserAvatar should handle connection errors gracefully', async () => {
  fetch.mockImplementationOnce(() => {
    throw new Error('Network error');
  });
    
  const result = await getUserAvatar('user1');
  expect(result).toBeNull();
});
