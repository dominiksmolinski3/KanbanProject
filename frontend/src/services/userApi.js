import { apiError } from './apiError';
import { read, readDeleted } from './http';

const USERS = '/api/users';

export const fetchUsers = async () => {
  const response = await fetch(USERS);
  return read(response);
};

export const fetchUser = async (userId) => {
  const response = await fetch(`${USERS}/${userId}`);
  return read(response);
};

export const deleteUser = async (userId) => {
  const response = await fetch(`${USERS}/${userId}`, { method: 'DELETE' });
  return readDeleted(response);
};

export const uploadUserAvatar = async (userId, file) => {
  const formData = new FormData();
  formData.append('file', file);
  const response = await fetch(`${USERS}/${userId}/avatar`, {
    method: 'POST',
    body: formData,
  });
  if (!response.ok) {
    throw await apiError(response);
  }
};

export const getUserAvatar = async (userId) => {
  try {
    const response = await fetch(`${USERS}/${userId}/avatar`, {
      headers: {
        'Accept': 'image/*, application/json',
        'Cache-Control': 'no-cache'
      }
    });
    return response.ok ? URL.createObjectURL(await response.blob()) : null;
  } catch {
    return null;
  }
};

export const getUserWipStatus = async (userId) => {
  const response = await fetch(`${USERS}/${userId}/wip-status`);
  return read(response);
};

export const updateUserWipLimit = async (userId, wipLimit) => {
  const response = await fetch(`${USERS}/${userId}/wip-limit`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(parseInt(wipLimit, 10))
  });
  return read(response);
};

export const updateUserLocale = async (userId, locale) => {
  const response = await fetch(`${USERS}/${userId}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ locale })
  });
  return read(response);
};
