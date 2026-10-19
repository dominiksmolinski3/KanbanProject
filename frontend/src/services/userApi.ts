import { deleteQuietly, getJson, patchJson, readVoid } from './http';
import type { Id, User, WipStatus } from '../types/api';

const USERS = '/api/users';

export const fetchUsers = () => getJson<User[]>(USERS);

export const fetchUser = (userId: Id) => getJson<User>(`${USERS}/${userId}`);

export const deleteUser = (userId: Id) => deleteQuietly(`${USERS}/${userId}`);

export const uploadUserAvatar = async (userId: Id, file: Blob) => {
  const formData = new FormData();
  formData.append('file', file);
  const response = await fetch(`${USERS}/${userId}/avatar`, {
    method: 'POST',
    body: formData,
  });
  return readVoid(response);
};

export const getUserAvatar = async (userId: Id) => {
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

export const getUserWipStatus = (userId: Id) => getJson<WipStatus>(`${USERS}/${userId}/wip-status`);

export const updateUserWipLimit = (userId: Id, wipLimit: number | string | null) =>
  patchJson<User>(`${USERS}/${userId}/wip-limit`, wipLimit === null ? null : parseInt(String(wipLimit), 10));

export const updateUserLocale = (userId: Id, locale: string) => patchJson<User>(`${USERS}/${userId}`, { locale });
