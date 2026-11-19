import { p256, p256_hasher } from '@noble/curves/nist.js';
import { bytesToNumberBE } from '@noble/curves/utils.js';
import { buffer, concat, randomBytes, utf8 } from './bytes';

export const PIN_LENGTH = 6;

const DST = utf8('KanbanProject-PIN-V1-P256_XMD:SHA-256_SSWU_RO_');
const PIN_SALT = utf8('KanbanProject PIN v1');
const Fn = p256.Point.Fn;

export const isPin = (pin: string): boolean => new RegExp(`^\\d{${PIN_LENGTH}}$`).test(pin);

export interface BlindedPin {
  blinded: Uint8Array;
  blind: bigint;
}

export function blindPin(userId: number, pin: string): BlindedPin {
  const point = p256_hasher.hashToCurve(utf8(`${userId}:${pin}`), { DST });
  let blind = 0n;
  while (blind === 0n) {
    blind = Fn.create(bytesToNumberBE(randomBytes(48)));
  }
  return { blinded: point.multiply(blind).toBytes(false), blind };
}

// The server answers with the x coordinate of an ECDH; either y gives the same x once unblinded.
export function unblindPin(evaluated: Uint8Array, blind: bigint): Uint8Array {
  const point = p256.Point.fromBytes(concat(new Uint8Array([0x02]), evaluated));
  return point.multiply(Fn.inv(blind)).toBytes(true).subarray(1);
}

export interface PinKeys {
  wrappingKey: CryptoKey;
  proof: Uint8Array;
}

export async function pinKeys(secret: Uint8Array): Promise<PinKeys> {
  const material = await crypto.subtle.importKey('raw', buffer(secret), 'HKDF', false, ['deriveKey', 'deriveBits']);
  const wrappingKey = await crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: buffer(PIN_SALT), info: buffer(utf8('identity key')) },
    material, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  const proof = new Uint8Array(await crypto.subtle.deriveBits(
    { name: 'HKDF', hash: 'SHA-256', salt: buffer(PIN_SALT), info: buffer(utf8('pin proof')) }, material, 256));
  return { wrappingKey, proof };
}

export async function proofVerifier(proof: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.digest('SHA-256', buffer(proof)));
}
