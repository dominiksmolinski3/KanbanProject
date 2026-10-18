import { apiError } from './apiError';
import type { Id } from '../types/api';

export class ConcurrentModificationError extends Error {
  constructor(what = 'item') {
    super(`The ${what} was changed by someone else`);
    this.name = 'ConcurrentModificationError';
  }
}

export const reorder = async <T>(endpoint: string, orderedIds: Id[], what: string): Promise<T> => {
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
