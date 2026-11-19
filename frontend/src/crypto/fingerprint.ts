import { fromBase64, toHex } from './bytes';

export function keyFingerprint(signatureKey: string | Uint8Array): string {
  const bytes = typeof signatureKey === 'string' ? fromBase64(signatureKey) : signatureKey;
  return toHex(bytes.subarray(0, 16)).match(/.{4}/g)?.join(' ') ?? '';
}
