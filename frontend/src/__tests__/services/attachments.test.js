import * as api from '../../services/api';

/**
 * The attachment client, and the two things about it that are easy to get quietly wrong.
 *
 * The upload must not set a Content-Type header — only the browser knows the multipart boundary it
 * is about to write, and a hand-set header produces a body the server cannot parse into a file.
 *
 * The download must go through `fetch` rather than a link click. The route is authenticated and an
 * `<a href>` carries no `Authorization` header, so a plain link would 401; fetching is what lets
 * the interceptor attach the token. The object URL it produces has to be revoked, or every
 * download pins its file in memory for the life of the tab.
 */
describe('task attachments', () => {
  beforeEach(() => {
    global.fetch = jest.fn();
    URL.createObjectURL = jest.fn().mockReturnValue('blob:mock-url');
    URL.revokeObjectURL = jest.fn();
  });

  describe('listing', () => {
    test('reads the attachments of one task', async () => {
      const stored = [{ id: 1, fileName: 'notes.txt', sizeBytes: 12, contentType: 'text/plain' }];
      fetch.mockResolvedValueOnce({ ok: true, json: async () => stored });

      const result = await api.fetchTaskAttachments(7);

      expect(fetch).toHaveBeenCalledWith('/api/tasks/7/attachments');
      expect(result).toEqual(stored);
    });

    test('a failure is an error rather than an empty list', async () => {
      fetch.mockResolvedValueOnce({ ok: false, status: 500 });

      await expect(api.fetchTaskAttachments(7)).rejects.toThrow('500');
    });
  });

  describe('uploading', () => {
    const file = (name, size) => {
      const blob = new File(['x'], name, { type: 'text/plain' });
      Object.defineProperty(blob, 'size', { value: size });
      return blob;
    };

    test('posts the file as multipart and lets the browser set the content type', async () => {
      fetch.mockResolvedValueOnce({ ok: true, json: async () => ({ id: 1 }) });

      await api.uploadTaskAttachment(7, file('notes.txt', 10));

      const [url, options] = fetch.mock.calls[0];
      expect(url).toBe('/api/tasks/7/attachments');
      expect(options.method).toBe('POST');
      expect(options.body).toBeInstanceOf(FormData);
      expect(options.body.get('file')).toBeTruthy();
      expect(options.headers).toBeUndefined();
    });

    test('a file over the limit is refused without a request', async () => {
      const tooBig = file('huge.bin', api.MAX_ATTACHMENT_SIZE + 1);

      await expect(api.uploadTaskAttachment(7, tooBig))
        .rejects.toMatchObject({ name: 'AttachmentUploadError', reason: 'tooLarge' });
      expect(fetch).not.toHaveBeenCalled();
    });

    test('a server that refuses the size says so, so the caller can name the limit', async () => {
      fetch.mockResolvedValueOnce({
        ok: false,
        status: 413,
        json: async () => ({ code: 'ATTACHMENT_TOO_LARGE' })
      });

      await expect(api.uploadTaskAttachment(7, file('notes.txt', 10)))
        .rejects.toMatchObject({ reason: 'tooLarge' });
    });

    test('storage not being configured is its own reason, because nobody can retry their way out of it', async () => {
      fetch.mockResolvedValueOnce({
        ok: false,
        status: 503,
        json: async () => ({ code: 'ATTACHMENT_STORAGE_UNAVAILABLE' })
      });

      await expect(api.uploadTaskAttachment(7, file('notes.txt', 10)))
        .rejects.toMatchObject({ reason: 'storageUnavailable' });
    });

    test('anything else is a plain failure', async () => {
      fetch.mockResolvedValueOnce({ ok: false, status: 500, json: async () => ({}) });

      await expect(api.uploadTaskAttachment(7, file('notes.txt', 10)))
        .rejects.toMatchObject({ reason: 'failed', status: 500 });
    });
  });

  describe('downloading', () => {
    test('fetches the bytes from the app and saves them under the stored name', async () => {
      const bytes = new Blob(['file contents']);
      fetch.mockResolvedValueOnce({ ok: true, blob: async () => bytes });
      const click = jest.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

      await api.downloadTaskAttachment(7, 3, 'report.pdf');

      expect(fetch).toHaveBeenCalledWith('/api/tasks/7/attachments/3/content');
      expect(URL.createObjectURL).toHaveBeenCalledWith(bytes);
      expect(click).toHaveBeenCalled();
      click.mockRestore();
    });

    test('revokes the object URL, so the file is not pinned in memory', async () => {
      fetch.mockResolvedValueOnce({ ok: true, blob: async () => new Blob(['x']) });
      const click = jest.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

      await api.downloadTaskAttachment(7, 3, 'report.pdf');

      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:mock-url');
      click.mockRestore();
    });

    test('leaves no anchor behind in the document', async () => {
      fetch.mockResolvedValueOnce({ ok: true, blob: async () => new Blob(['x']) });
      const click = jest.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});

      await api.downloadTaskAttachment(7, 3, 'report.pdf');

      expect(document.querySelectorAll('a')).toHaveLength(0);
      click.mockRestore();
    });

    test('a refused download creates no object URL', async () => {
      fetch.mockResolvedValueOnce({ ok: false, status: 404 });

      await expect(api.downloadTaskAttachment(7, 3, 'report.pdf')).rejects.toThrow('404');
      expect(URL.createObjectURL).not.toHaveBeenCalled();
    });
  });

  describe('deleting', () => {
    test('sends the delete', async () => {
      fetch.mockResolvedValueOnce({ ok: true, status: 204 });

      await expect(api.deleteTaskAttachment(7, 3)).resolves.toBe(true);
      expect(fetch).toHaveBeenCalledWith('/api/tasks/7/attachments/3', { method: 'DELETE' });
    });

    test('an attachment already gone counts as deleted', async () => {
      fetch.mockResolvedValueOnce({ ok: false, status: 404 });

      await expect(api.deleteTaskAttachment(7, 3)).resolves.toBe(true);
    });

    test('any other failure is reported', async () => {
      fetch.mockResolvedValueOnce({ ok: false, status: 500 });

      await expect(api.deleteTaskAttachment(7, 3)).rejects.toThrow('500');
    });
  });
});
