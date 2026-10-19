export const apiOrigin = (): string =>
  ((import.meta.env.VITE_API_ORIGIN as string | undefined) ?? '').replace(/\/+$/, '');

const API_PATH = /^\/api(?:[/?#]|$)/;

export const apiUrl = (path: string): string => (API_PATH.test(path) ? `${apiOrigin()}${path}` : path);

export const socketOrigin = (): string =>
  apiOrigin() || (typeof window !== 'undefined' ? window.location.origin : '');
