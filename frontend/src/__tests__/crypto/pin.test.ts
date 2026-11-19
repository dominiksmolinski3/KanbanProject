/**
 * @jest-environment node
 */
import { sha256 } from '@noble/hashes/sha2.js';
import { p256 } from '@noble/curves/nist.js';
import { blindPin, isPin, pinKeys, proofVerifier, unblindPin } from '../../crypto/pin';
import { fromBase64, toBase64, toHex } from '../../crypto/bytes';

const Fn = p256.Point.Fn;

// The same derivation as PinEvaluator on the server.
const serverScalar = (secret: string): bigint => {
  const hashed = sha256(new TextEncoder().encode(`KanbanProject PIN key v1${secret}`));
  return (BigInt(`0x${toHex(hashed)}`) % (Fn.ORDER - 1n)) + 1n;
};

// What the server answers: the x coordinate of k times the blinded point, as ECDH gives it.
const evaluate = (secret: string, blinded: Uint8Array): Uint8Array =>
  p256.Point.fromBytes(blinded).multiply(serverScalar(secret)).toBytes(true).subarray(1);

describe('the PIN exchange', () => {
  test('matches the server vector in PinEvaluatorTest', () => {
    const evaluated = evaluate('test-pin-secret', p256.Point.BASE.toBytes(false));

    expect(toHex(evaluated)).toBe('9dc2918299876cd3b23bf5dab8d64878b286c1a79edbc9c0ccbe517643d67eb8');
  });

  test('gives the same secret for the same PIN whatever the blind, so the server never sees the PIN', () => {
    const first = blindPin(7, '123456');
    const second = blindPin(7, '123456');

    expect(toBase64(first.blinded)).not.toBe(toBase64(second.blinded));
    expect(toHex(unblindPin(evaluate('s', first.blinded), first.blind)))
      .toBe(toHex(unblindPin(evaluate('s', second.blinded), second.blind)));
  });

  test('gives a different secret for another PIN, another account or another server secret', () => {
    const secretOf = (userId: number, pin: string, server: string) => {
      const blinded = blindPin(userId, pin);
      return toHex(unblindPin(evaluate(server, blinded.blinded), blinded.blind));
    };
    const reference = secretOf(7, '123456', 's');

    expect(secretOf(7, '123457', 's')).not.toBe(reference);
    expect(secretOf(8, '123456', 's')).not.toBe(reference);
    expect(secretOf(7, '123456', 't')).not.toBe(reference);
  });

  test('derives a wrapping key and a proof, and the server stores only a hash of the proof', async () => {
    const secret = fromBase64(toBase64(new Uint8Array(32).fill(3)));
    const keys = await pinKeys(secret);
    const again = await pinKeys(secret);

    expect(keys.wrappingKey.extractable).toBe(false);
    expect(toHex(keys.proof)).toBe(toHex(again.proof));
    expect(toHex(await proofVerifier(keys.proof))).not.toBe(toHex(keys.proof));
    expect(await proofVerifier(keys.proof)).toHaveLength(32);
  });

  test('accepts exactly six digits', () => {
    expect(isPin('012345')).toBe(true);
    expect(isPin('12345')).toBe(false);
    expect(isPin('1234567')).toBe(false);
    expect(isPin('12345a')).toBe(false);
  });
});
