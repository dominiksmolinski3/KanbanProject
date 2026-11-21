/**
 * @jest-environment node
 */
import { isInlineImage, MAX_FILE_BYTES, openFile, sealFile } from '../../crypto/attachments';

describe('attachment encryption', () => {
  test('a file opens with the key that sealed it, and the stored bytes are not the file', async () => {
    const plain = new TextEncoder().encode('quarterly numbers');

    const sealed = await sealFile(plain);

    expect(new TextDecoder().decode(sealed.bytes)).not.toContain('quarterly');
    expect(await openFile(sealed.key, sealed.bytes)).toEqual(plain);
  });

  test('every file gets its own key', async () => {
    const plain = new Uint8Array([1, 2, 3]);

    expect((await sealFile(plain)).key).not.toBe((await sealFile(plain)).key);
  });

  test('a changed byte, a wrong key and a truncated file are all refused', async () => {
    const sealed = await sealFile(new Uint8Array([9, 8, 7, 6]));
    const tampered = sealed.bytes.slice();
    tampered[tampered.length - 1] ^= 1;

    await expect(openFile(sealed.key, tampered)).rejects.toThrow();
    await expect(openFile((await sealFile(new Uint8Array([1]))).key, sealed.bytes)).rejects.toThrow();
    await expect(openFile(sealed.key, sealed.bytes.slice(0, 5))).rejects.toThrow('truncated');
  });

  test('only common raster images are previewed inline', () => {
    expect(isInlineImage('image/png')).toBe(true);
    expect(isInlineImage('image/svg+xml')).toBe(false);
    expect(isInlineImage('application/pdf')).toBe(false);
    expect(MAX_FILE_BYTES).toBeLessThan(10 * 1024 * 1024);
  });
});
