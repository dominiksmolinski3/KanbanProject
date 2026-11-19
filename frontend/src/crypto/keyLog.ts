import { buffer, concat, fromBase64, toBase64, utf8 } from './bytes';

export const KEY_LOG_LABEL = utf8('KanbanProject key log v1');

export type KeyChange = 'IDENTITY' | 'DEVICE_ADDED' | 'DEVICE_REMOVED';

export interface KeyLogEntry {
  id: number;
  change: KeyChange;
  subject: string;
  prevHash: string | null;
  entryHash: string;
  at: string;
}

export async function entryHash(prevHash: Uint8Array | null, change: KeyChange, subject: Uint8Array): Promise<Uint8Array> {
  const parts = [KEY_LOG_LABEL, ...(prevHash ? [prevHash] : []), utf8(change), new Uint8Array([0]), subject];
  return new Uint8Array(await crypto.subtle.digest('SHA-256', buffer(concat(...parts))));
}

export async function chainHolds(entries: KeyLogEntry[], lastSeenHash: string | null): Promise<boolean> {
  let previous = lastSeenHash;
  for (const entry of entries) {
    if ((entry.prevHash ?? null) !== previous) {
      return false;
    }
    const expected = await entryHash(previous ? fromBase64(previous) : null, entry.change, fromBase64(entry.subject));
    if (toBase64(expected) !== entry.entryHash) {
      return false;
    }
    previous = entry.entryHash;
  }
  return true;
}
