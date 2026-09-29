import i18next from 'i18next';

const FALLBACK = 'Something went wrong. Try again.';

export class ApiError extends Error {
  constructor(message, { status, code } = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

function parseJson(text) {
  try {
    return text ? JSON.parse(text) : null;
  } catch {
    return null;
  }
}

function statusKey(status) {
  if (status === 413) return 'errors.status.tooLarge';
  if (status === 429) return 'errors.codes.TOO_MANY_REQUESTS';
  if (status >= 500) return 'errors.status.server';
  return null;
}

function isSentence(value) {
  return typeof value === 'string' && value.trim() !== '' && !/^\s*[{[<]/.test(value);
}

export function errorText(code, status, serverMessage) {
  const keys = [code && `errors.codes.${code}`, statusKey(status), 'errors.generic'].filter(Boolean);
  if (i18next.isInitialized) {
    const key = keys.find((candidate) => i18next.exists(candidate));
    if (key) return i18next.t(key);
  }
  return isSentence(serverMessage) ? serverMessage : FALLBACK;
}

async function readBody(response) {
  try {
    if (typeof response.text === 'function') return parseJson(await response.text());
    if (typeof response.json === 'function') return await response.json();
  } catch {
    return null;
  }
  return null;
}

export function errorFromBody(status, body) {
  const code = body && typeof body.code === 'string' ? body.code : undefined;
  return new ApiError(errorText(code, status, body && body.message), { status, code });
}

export async function apiError(response) {
  return errorFromBody(response.status, await readBody(response));
}
