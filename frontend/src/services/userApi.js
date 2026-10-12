import { apiError } from './apiError';

const USERS = '/api/users';

export const fetchUsers = async () => {
  try {
    const response = await fetch(USERS);
    if (!response.ok) {
      throw await apiError(response);
    }
    return await response.json();
  } catch (error) {
    console.error('Error fetching users:', error);
    throw error;
  }
};

export const fetchUser = async (userId) => {
  try {
    const response = await fetch(`${USERS}/${userId}`);
    if (!response.ok) {
      throw await apiError(response);
    }
    return await response.json();
  } catch (error) {
    console.error(`Error fetching user ${userId}:`, error);
    throw error;
  }
};

export const deleteUser = async (userId) => {
  try {
    const response = await fetch(`${USERS}/${userId}`, {
      method: 'DELETE'
    });
    
    if (!response.ok && response.status !== 404) {
      throw await apiError(response);
    }
    
    return true;
  } catch (error) {
    console.error(`Error deleting user ${userId}:`, error);
    throw error;
  }
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
    
    if (!response.ok) {
      console.warn(`Could not fetch avatar for user ${userId}: ${response.status}`);
      return null;
    }
    
    const blob = await response.blob();
    return URL.createObjectURL(blob);
  } catch (error) {
    console.warn(`Error fetching avatar for user ${userId}:`, error);
    return null;
  }
};

export async function getUserWipStatus(userId) {
  try {
    const response = await fetch(`${USERS}/${userId}/wip-status`);

    if (!response.ok) {
      throw await apiError(response);
    }

    return await response.json();
  } catch (error) {
    console.error('Error checking user WIP status:', error);
    throw error;
  }
}

export async function updateUserWipLimit(userId, wipLimit) {
  try {
    const response = await fetch(`${USERS}/${userId}/wip-limit`, {
      method: 'PATCH',
      headers: {
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(parseInt(wipLimit)), 
    });

    if (!response.ok) {
      throw await apiError(response);
    }

    return await response.json();
  } catch (error) {
    console.error('Error updating user WIP limit:', error);
    throw error;
  }
}

export async function updateUserLocale(userId, locale) {
  const response = await fetch(`${USERS}/${userId}`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ locale }),
  });

  if (!response.ok) {
    throw await apiError(response);
  }

  return await response.json();
}
