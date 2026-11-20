/**
 * @jest-environment node
 */
import { downloadFile, uploadFiles } from '../../context/messages/files';
import { messagesApi } from '../../services/messagesApi';

jest.mock('../../services/messagesApi', () => ({ messagesApi: { uploadBlob: jest.fn(), downloadBlob: jest.fn() } }));

const api = messagesApi as unknown as { uploadBlob: jest.Mock; downloadBlob: jest.Mock };

describe('message files', () => {
  test('files are encrypted before they are uploaded, and come back readable with the key in the message', async () => {
    const stored = new Map<number, Uint8Array>();
    api.uploadBlob.mockImplementation(async (_conversation: number, bytes: Uint8Array) => {
      stored.set(stored.size + 1, bytes);
      return { id: stored.size, size: bytes.length };
    });
    api.downloadBlob.mockImplementation(async (_conversation: number, id: number) => stored.get(id));
    const files = [new File(['first file'], 'a.txt', { type: 'text/plain' }), new File(['second'], 'b.bin')];

    const refs = await uploadFiles(9, files);

    expect(refs).toEqual([
      expect.objectContaining({ blobId: 1, name: 'a.txt', type: 'text/plain', size: 10 }),
      expect.objectContaining({ blobId: 2, name: 'b.bin', type: 'application/octet-stream', size: 6 }),
    ]);
    expect(api.uploadBlob).toHaveBeenCalledWith(9, expect.any(Uint8Array));
    expect(new TextDecoder().decode(stored.get(1))).not.toContain('first file');
    const opened = await downloadFile(9, refs[0]);
    expect(opened.type).toBe('text/plain');
    expect(await opened.text()).toBe('first file');
  });
});
