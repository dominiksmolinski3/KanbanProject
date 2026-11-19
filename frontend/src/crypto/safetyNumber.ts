import { sha512 } from '@noble/hashes/sha2.js';
import { concat, fromBase64, utf8 } from './bytes';

const ITERATIONS = 5200;
const VERSION = new Uint8Array([0, 0]);

function half(userId: number, identityKey: Uint8Array): string {
  const id = utf8(String(userId));
  let hash = concat(VERSION, identityKey, id);
  for (let i = 0; i < ITERATIONS; i++) {
    hash = sha512(concat(hash, identityKey));
  }
  let digits = '';
  for (let chunk = 0; chunk < 6; chunk++) {
    let value = 0;
    for (let i = 0; i < 5; i++) {
      value = value * 256 + hash[chunk * 5 + i];
    }
    digits += String(value % 100000).padStart(5, '0');
  }
  return digits;
}

export interface Party {
  userId: number;
  identityKey: string | Uint8Array;
}

const keyOf = (party: Party) => (typeof party.identityKey === 'string' ? fromBase64(party.identityKey) : party.identityKey);

export function safetyNumber(one: Party, other: Party): string {
  const [first, second] = one.userId < other.userId ? [one, other] : [other, one];
  return half(first.userId, keyOf(first)) + half(second.userId, keyOf(second));
}

export const safetyNumberGroups = (digits: string): string[] => digits.match(/.{5}/g) ?? [];
