import { ApiError } from '../../services/apiError';
import {
  deleteChecked,
  deleteQuietly,
  getJson,
  patchJson,
  postVoid,
  queryString,
  readUnless,
} from '../../services/http';

global.fetch = jest.fn();

beforeEach(() => {
  fetch.mockReset();
});

const answer = (status, body = {}) => ({ ok: status < 400, status, json: async () => body });

describe('request helpers', () => {
  test('a read sends the path and nothing else', async () => {
    fetch.mockResolvedValueOnce(answer(200, { id: 1 }));

    await expect(getJson('/api/things/1')).resolves.toEqual({ id: 1 });

    expect(fetch).toHaveBeenCalledWith('/api/things/1');
  });

  test('a write without a body sends the method alone', async () => {
    fetch.mockResolvedValueOnce(answer(200, {}));

    await patchJson('/api/things/1/change');

    expect(fetch).toHaveBeenCalledWith('/api/things/1/change', { method: 'PATCH' });
  });

  test('a falsy body is still sent, not mistaken for none', async () => {
    fetch.mockResolvedValueOnce(answer(200, {}));

    await patchJson('/api/things/1/limit', 0);

    expect(fetch).toHaveBeenCalledWith('/api/things/1/limit', {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: '0',
    });
  });

  test('a void write resolves to nothing and refuses a failed answer', async () => {
    fetch.mockResolvedValueOnce(answer(204));
    await expect(postVoid('/api/things', { name: 'a' })).resolves.toBeUndefined();

    fetch.mockResolvedValueOnce(answer(400, { code: 'NOPE' }));
    await expect(postVoid('/api/things', { name: 'a' })).rejects.toBeInstanceOf(ApiError);
  });

  test('a delete that must exist refuses a 404, one that may be gone does not', async () => {
    fetch.mockResolvedValue(answer(404));

    await expect(deleteChecked('/api/things/1')).rejects.toMatchObject({ status: 404 });
    await expect(deleteQuietly('/api/things/1')).resolves.toBe(true);
  });
});

describe('queryString', () => {
  test('leaves out what is absent and keeps what is merely falsy', () => {
    expect(queryString({ boardId: undefined, other: null, page: 0, with: '', size: 25 }))
      .toBe('page=0&with=&size=25');
  });

  test('is empty when nothing is given', () => {
    expect(queryString({})).toBe('');
  });
});

describe('readUnless', () => {
  const refusal = () => new Error('refused');

  test('throws the caller refusal for its own code on a 400', async () => {
    await expect(readUnless(answer(400, { code: 'WIP_FULL' }), 'WIP_FULL', refusal)).rejects.toThrow('refused');
  });

  test('falls back to the API error for any other failure', async () => {
    await expect(readUnless(answer(400, { code: 'OTHER' }), 'WIP_FULL', refusal))
      .rejects.toMatchObject({ status: 400, code: 'OTHER' });
    await expect(readUnless(answer(500, {}), 'WIP_FULL', refusal)).rejects.toMatchObject({ status: 500 });
  });

  test('reads the body of a good answer', async () => {
    await expect(readUnless(answer(200, { id: 7 }), 'WIP_FULL', refusal)).resolves.toEqual({ id: 7 });
  });
});
