import { apiError } from './apiError';

export class ConcurrentModificationError extends Error {
  constructor(what = 'item') {
    super(`The ${what} was changed by someone else`);
    this.name = 'ConcurrentModificationError';
  }
}

export const reorder = async (endpoint, orderedIds, what) => {
  const response = await fetch(`${endpoint}/positions`, {
    method: 'PATCH',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ orderedIds })
  });

  if (response.status === 409) {
    throw new ConcurrentModificationError(what);
  }
  if (!response.ok) {
    throw await apiError(response);
  }

  return await response.json();
};
