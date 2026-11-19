/**
 * @jest-environment node
 */
import { toBase64 } from '../../crypto/bytes';
import { chainHolds, entryHash, type KeyLogEntry } from '../../crypto/keyLog';

const IDENTITY_KEY = new Uint8Array(32).fill(1);
const DEVICE_KEY = new Uint8Array(32).fill(2);
const FIRST = 'OAuuAxUQjBe1OIqxRCiAhc3o1eFGXmCqRXcdiXlfZCc=';
const SECOND = '8UE3FBPN1DIxRm0d8kHqFfpiN56yI/AKBFkvH29I+Ug=';

const entries: KeyLogEntry[] = [
  { id: 1, change: 'IDENTITY', subject: toBase64(IDENTITY_KEY), prevHash: null, entryHash: FIRST, at: '2026-11-19T10:00:00Z' },
  { id: 2, change: 'DEVICE_ADDED', subject: toBase64(DEVICE_KEY), prevHash: FIRST, entryHash: SECOND, at: '2026-11-19T10:01:00Z' },
];

describe('the key log', () => {
  test('hashes entries to the bytes KeyDirectoryTest expects on the server', async () => {
    const first = await entryHash(null, 'IDENTITY', IDENTITY_KEY);

    expect(toBase64(first)).toBe(FIRST);
    expect(toBase64(await entryHash(first, 'DEVICE_ADDED', DEVICE_KEY))).toBe(SECOND);
  });

  test('holds from the start and from the last hash a client remembers', async () => {
    expect(await chainHolds(entries, null)).toBe(true);
    expect(await chainHolds(entries.slice(1), FIRST)).toBe(true);
  });

  test('breaks when an entry was rewritten or does not follow the hash a client remembers', async () => {
    expect(await chainHolds([{ ...entries[0], subject: toBase64(DEVICE_KEY) }], null)).toBe(false);
    expect(await chainHolds(entries.slice(1), SECOND)).toBe(false);
  });
});
