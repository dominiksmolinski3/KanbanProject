import { apiError, errorFromBody, type ErrorBody } from './apiError';

const RETRY_DELAY_MS = 1000;

export const read = async <T>(response: Response): Promise<T> => {
  if (!response.ok) {
    throw await apiError(response);
  }
  return response.json();
};

export const readVoid = async (response: Response): Promise<void> => {
  if (!response.ok) {
    throw await apiError(response);
  }
};

export const readOk = async (response: Response): Promise<true> => {
  await readVoid(response);
  return true;
};

export const readDeleted = async (response: Response): Promise<true> => {
  if (!response.ok && response.status !== 404) {
    throw await apiError(response);
  }
  return true;
};

export const readUnless = async <T>(response: Response, code: string, refusal: () => Error): Promise<T> => {
  if (!response.ok) {
    const body: ErrorBody = await response.json().catch(() => ({}));
    if (response.status === 400 && body.code === code) {
      throw refusal();
    }
    throw errorFromBody(response.status, body);
  }
  return response.json();
};

const send = (method: string, path: string, body?: unknown): Promise<Response> =>
  body === undefined
    ? fetch(path, { method })
    : fetch(path, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

export const getJson = async <T>(path: string): Promise<T> => read<T>(await fetch(path));

export const postJson = async <T>(path: string, body?: unknown): Promise<T> => read<T>(await send('POST', path, body));

export const patchJson = async <T>(path: string, body?: unknown): Promise<T> => read<T>(await send('PATCH', path, body));

export const putJson = async <T>(path: string, body?: unknown): Promise<T> => read<T>(await send('PUT', path, body));

export const deleteJson = async <T>(path: string): Promise<T> => read<T>(await send('DELETE', path));

export const postVoid = async (path: string, body?: unknown): Promise<void> => readVoid(await send('POST', path, body));

export const patchVoid = async (path: string, body?: unknown): Promise<void> => readVoid(await send('PATCH', path, body));

export const deleteChecked = async (path: string): Promise<true> => readOk(await send('DELETE', path));

export const deleteQuietly = async (path: string): Promise<true> => readDeleted(await send('DELETE', path));

export const queryString = (params: Record<string, string | number | null | undefined>): string => {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null) {
      query.set(key, String(value));
    }
  }
  return query.toString();
};

export const withRetries = async <T>(attempt: () => Promise<T>, retries: number): Promise<T> => {
  for (let tried = 1; ; tried++) {
    try {
      return await attempt();
    } catch (error) {
      if (tried >= retries) throw error;
      await new Promise((resolve) => setTimeout(resolve, RETRY_DELAY_MS));
    }
  }
};
