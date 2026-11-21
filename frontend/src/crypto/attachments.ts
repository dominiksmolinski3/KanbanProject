import { buffer, concat, fromBase64, randomBytes, toBase64 } from './bytes';

export const MAX_FILE_BYTES = 9 * 1024 * 1024;

const IV_BYTES = 12;

export interface SealedFile {
  key: string;
  bytes: Uint8Array;
}

export async function sealFile(plain: Uint8Array): Promise<SealedFile> {
  const raw = randomBytes(32);
  const iv = randomBytes(IV_BYTES);
  const key = await crypto.subtle.importKey('raw', buffer(raw), 'AES-GCM', false, ['encrypt']);
  const sealed = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: buffer(iv) }, key, buffer(plain)));
  return { key: toBase64(raw), bytes: concat(iv, sealed) };
}

export async function openFile(keyBase64: string, bytes: Uint8Array): Promise<Uint8Array> {
  if (bytes.length <= IV_BYTES) throw new Error('Attachment is truncated');
  const key = await crypto.subtle.importKey('raw', buffer(fromBase64(keyBase64)), 'AES-GCM', false, ['decrypt']);
  const plain = await crypto.subtle.decrypt({ name: 'AES-GCM', iv: buffer(bytes.subarray(0, IV_BYTES)) }, key,
    buffer(bytes.subarray(IV_BYTES)));
  return new Uint8Array(plain);
}

const INLINE_IMAGES = new Set(['image/png', 'image/jpeg', 'image/gif', 'image/webp']);

export const isInlineImage = (type: string) => INLINE_IMAGES.has(type);
