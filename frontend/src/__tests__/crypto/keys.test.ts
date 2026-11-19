/**
 * @jest-environment node
 */
import { decodeMlsMessage } from 'ts-mls';
import { makeKeyPackageRef } from 'ts-mls/keyPackage.js';
import {
  ciphersuite,
  createDevice,
  createIdentity,
  createKeyPackages,
  deviceSignaturePayload,
  open,
  newStorageKey,
  restoreIdentity,
  seal,
  RecoveryKeyMismatchError,
} from '../../crypto/keys';
import { generateRecoveryKey, normalizeRecoveryKey, recoveryKeyGroups, RECOVERY_KEY_LENGTH } from '../../crypto/recoveryKey';
import { buffer, fromBase64, toBase64, utf8 } from '../../crypto/bytes';
import { keyFingerprint } from '../../crypto/fingerprint';

const verifyWith = async (publicKey: Uint8Array, signature: Uint8Array, payload: Uint8Array) => {
  const key = await crypto.subtle.importKey('raw', buffer(publicKey), 'Ed25519', false, ['verify']);
  return crypto.subtle.verify('Ed25519', key, buffer(signature), buffer(payload));
};

describe('recovery keys', () => {
  test('are 48 symbols from an alphabet without look-alike characters, in 12 groups of 4', () => {
    const key = generateRecoveryKey();

    expect(key).toHaveLength(RECOVERY_KEY_LENGTH);
    expect(key).toMatch(/^[A-HJ-NP-Z2-9]{48}$/);
    expect(recoveryKeyGroups(key)).toHaveLength(12);
    expect(recoveryKeyGroups(key).join('')).toBe(key);
  });

  test('are not the same twice', () => {
    expect(generateRecoveryKey()).not.toBe(generateRecoveryKey());
  });

  test('read back however they were typed, and refuse anything that cannot be one', () => {
    const key = generateRecoveryKey();
    const typed = recoveryKeyGroups(key).join(' - ').toLowerCase();

    expect(normalizeRecoveryKey(typed)).toBe(key);
    expect(normalizeRecoveryKey(key.slice(1))).toBeNull();
    expect(normalizeRecoveryKey(`${key.slice(1)}0`)).toBeNull();
    expect(normalizeRecoveryKey(`${key.slice(1)}O`)).toBeNull();
  });
});

describe('the identity key', () => {
  test('is wrapped so that only the same recovery key opens it', async () => {
    const recoveryKey = generateRecoveryKey();
    const created = await createIdentity(recoveryKey);

    expect(created.publicKey).toHaveLength(32);
    const restored = await restoreIdentity(recoveryKey, created);
    const payload = utf8('proof');
    const signature = new Uint8Array(await crypto.subtle.sign('Ed25519', restored, buffer(payload)));
    expect(await verifyWith(created.publicKey, signature, payload)).toBe(true);

    await expect(restoreIdentity(generateRecoveryKey(), created)).rejects.toBeInstanceOf(RecoveryKeyMismatchError);
  });

  test('is bound to its own public key, so a wrapped key moved under another public key does not open', async () => {
    const recoveryKey = generateRecoveryKey();
    const first = await createIdentity(recoveryKey);
    const second = await createIdentity(recoveryKey);

    await expect(restoreIdentity(recoveryKey, { publicKey: second.publicKey, wrappedPrivate: first.wrappedPrivate }))
      .rejects.toBeInstanceOf(RecoveryKeyMismatchError);
  });

  test('cannot be read back out of the browser once imported', async () => {
    const created = await createIdentity(generateRecoveryKey());

    expect(created.privateKey.extractable).toBe(false);
    await expect(crypto.subtle.exportKey('pkcs8', created.privateKey)).rejects.toThrow();
  });
});

describe('a device', () => {
  test('carries a credential naming its account and a signature from the identity key', async () => {
    const identity = await createIdentity(generateRecoveryKey());
    const { secrets, identitySignature } = await createDevice(42, identity.privateKey);

    expect(new TextDecoder().decode(secrets.credential)).toMatch(/^u42\.[0-9a-f]{32}$/);
    expect(secrets.signatureKey).toHaveLength(32);
    expect(identitySignature).toHaveLength(64);
    expect(await verifyWith(identity.publicKey, identitySignature,
      deviceSignaturePayload(secrets.signatureKey, secrets.credential))).toBe(true);
  });

  test('signs a payload the server can rebuild: the label, then the device key, then the credential', () => {
    const payload = deviceSignaturePayload(new Uint8Array([1, 2]), utf8('u1.x'));

    expect(new TextDecoder().decode(payload.subarray(0, 33))).toBe('KanbanProject messaging device v1');
    expect(Array.from(payload.subarray(33, 35))).toEqual([1, 2]);
    expect(new TextDecoder().decode(payload.subarray(35))).toBe('u1.x');
  });
});

describe('key packages', () => {
  test('are RFC 9420 key packages signed by the device key, with the reference the server indexes', async () => {
    const identity = await createIdentity(generateRecoveryKey());
    const { secrets } = await createDevice(7, identity.privateKey);
    const now = Date.UTC(2026, 10, 19);
    const [generated] = await createKeyPackages(secrets, 1, false, now);

    const decoded = decodeMlsMessage(generated.upload.data, 0)?.[0];
    if (!decoded || decoded.wireformat !== 'mls_key_package') throw new Error('not a key package');
    expect(decoded.keyPackage.cipherSuite).toBe('MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519');
    expect(Array.from(decoded.keyPackage.leafNode.signaturePublicKey)).toEqual(Array.from(secrets.signatureKey));
    expect(decoded.keyPackage.leafNode.credential).toEqual({ credentialType: 'basic', identity: secrets.credential });
    expect(decoded.keyPackage.leafNode.lifetime.notAfter - decoded.keyPackage.leafNode.lifetime.notBefore)
      .toBe(BigInt(90 * 24 * 3600 + 3600));
    const impl = await ciphersuite();
    expect(Array.from(generated.upload.ref)).toEqual(Array.from(await makeKeyPackageRef(decoded.keyPackage, impl.hash)));
    expect(generated.upload.lastResort).toBe(false);
    expect(generated.secret.initPrivateKey.length).toBeGreaterThan(0);
  });

  test('each have their own reference', async () => {
    const identity = await createIdentity(generateRecoveryKey());
    const { secrets } = await createDevice(7, identity.privateKey);
    const packages = await createKeyPackages(secrets, 3, true);

    expect(new Set(packages.map(({ upload }) => toBase64(upload.ref))).size).toBe(3);
    expect(packages.every(({ upload }) => upload.lastResort)).toBe(true);
  });
});

describe('sealing local secrets', () => {
  test('opens only with the same key and the same label', async () => {
    const key = await newStorageKey();
    const sealed = await seal(key, utf8('secret'), utf8('label'));

    expect(new TextDecoder().decode(await open(key, sealed, utf8('label')))).toBe('secret');
    await expect(open(key, sealed, utf8('other'))).rejects.toThrow();
    await expect(open(await newStorageKey(), sealed, utf8('label'))).rejects.toThrow();
  });
});

describe('fingerprints', () => {
  test('show the first 16 bytes of a key as eight groups of hex', () => {
    const key = new Uint8Array(32).map((_, index) => index);

    expect(keyFingerprint(key)).toBe('0001 0203 0405 0607 0809 0a0b 0c0d 0e0f');
    expect(keyFingerprint(toBase64(key))).toBe(keyFingerprint(fromBase64(toBase64(key))));
  });
});
