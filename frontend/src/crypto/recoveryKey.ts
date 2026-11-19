// 32 symbols, so each random byte maps to one without bias; 0, 1, I and O are left out as easy to misread.
const ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

export const RECOVERY_KEY_LENGTH = 48;
const GROUP = 4;

export function generateRecoveryKey(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(RECOVERY_KEY_LENGTH));
  return Array.from(bytes, (byte) => ALPHABET[byte % ALPHABET.length]).join('');
}

export function recoveryKeyGroups(key: string): string[] {
  const groups: string[] = [];
  for (let i = 0; i < key.length; i += GROUP) {
    groups.push(key.slice(i, i + GROUP));
  }
  return groups;
}

export function normalizeRecoveryKey(input: string): string | null {
  const key = input.toUpperCase().replace(/[\s-]/g, '');
  if (key.length !== RECOVERY_KEY_LENGTH) {
    return null;
  }
  return [...key].every((symbol) => ALPHABET.includes(symbol)) ? key : null;
}

export const recoveryKeyConfirmed = (recoveryKey: string, saved: boolean, check: string): boolean =>
  saved && check.trim().toUpperCase() === recoveryKeyGroups(recoveryKey).at(-1);
