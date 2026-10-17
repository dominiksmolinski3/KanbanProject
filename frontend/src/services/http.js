import { apiError } from './apiError';

const RETRY_DELAY_MS = 1000;

export const read = async (response) => {
  if (!response.ok) {
    throw await apiError(response);
  }
  return response.json();
};

export const readDeleted = async (response) => {
  if (!response.ok && response.status !== 404) {
    throw await apiError(response);
  }
  return true;
};

export const withRetries = async (attempt, retries) => {
  for (let tried = 1; ; tried++) {
    try {
      return await attempt();
    } catch (error) {
      if (tried >= retries) throw error;
      await new Promise(resolve => setTimeout(resolve, RETRY_DELAY_MS));
    }
  }
};
