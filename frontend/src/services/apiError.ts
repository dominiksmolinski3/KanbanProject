import i18next from 'i18next';

const FALLBACK = 'Something went wrong. Try again.';

interface ApiErrorDetails {
  status?: number;
  code?: string;
}

export interface ErrorBody {
  code?: unknown;
  message?: unknown;
}

export class ApiError extends Error {
  status?: number;
  code?: string;

  constructor(message: string, { status, code }: ApiErrorDetails = {}) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

function parseJson(text: string): ErrorBody | null {
  try {
    return text ? JSON.parse(text) : null;
  } catch {
    return null;
  }
}

function statusKey(status: number): string | null {
  if (status === 413) return 'errors.status.tooLarge';
  if (status === 429) return 'errors.codes.TOO_MANY_REQUESTS';
  if (status >= 500) return 'errors.status.server';
  return null;
}

function isSentence(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== '' && !/^\s*[{[<]/.test(value);
}

export function errorText(code: string | undefined, status: number, serverMessage?: unknown): string {
  const keys = [code && `errors.codes.${code}`, statusKey(status), 'errors.generic'].filter(
    (key): key is string => Boolean(key)
  );
  if (i18next.isInitialized) {
    const key = keys.find((candidate) => i18next.exists(candidate));
    if (key) return i18next.t(key);
  }
  return isSentence(serverMessage) ? serverMessage : FALLBACK;
}

async function readBody(response: Response): Promise<ErrorBody | null> {
  try {
    if (typeof response.text === 'function') return parseJson(await response.text());
    if (typeof response.json === 'function') return await response.json();
  } catch {
    return null;
  }
  return null;
}

export function errorFromBody(status: number, body: ErrorBody | null | undefined): ApiError {
  const code = body && typeof body.code === 'string' ? body.code : undefined;
  return new ApiError(errorText(code, status, body && body.message), { status, code });
}

export async function apiError(response: Response): Promise<ApiError> {
  return errorFromBody(response.status, await readBody(response));
}
