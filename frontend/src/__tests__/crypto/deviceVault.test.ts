/**
 * @jest-environment node
 */
import { sha256 } from '@noble/hashes/sha2.js';
import { p256 } from '@noble/curves/nist.js';
import { createDeviceVault, NoDeviceError, NoIdentityError, NoPinError } from '../../crypto/deviceVault';
import { DEVICE_INFO, memoryKeyStore, readLocalDevice, type KeyStore, type LocalDevice } from '../../crypto/keyStore';
import { generateRecoveryKey } from '../../crypto/recoveryKey';
import { buffer, toBase64, toHex } from '../../crypto/bytes';
import { pinSignaturePayload, recoverySignaturePayload, RecoveryKeyMismatchError } from '../../crypto/keys';

const SERVER_SCALAR = (() => {
  const hashed = sha256(new TextEncoder().encode('KanbanProject PIN key v1test'));
  return (BigInt(`0x${toHex(hashed)}`) % (p256.Point.Fn.ORDER - 1n)) + 1n;
})();

const evaluate = (blinded: Uint8Array): Uint8Array =>
  p256.Point.fromBytes(blinded).multiply(SERVER_SCALAR).toBytes(true).subarray(1);

const verifies = async (publicKey: Uint8Array, signature: Uint8Array, payload: Uint8Array) => {
  const key = await crypto.subtle.importKey('raw', buffer(publicKey), 'Ed25519', false, ['verify']);
  return crypto.subtle.verify('Ed25519', key, buffer(signature), buffer(payload));
};

describe('the device vault', () => {
  let store: KeyStore;

  beforeEach(() => {
    store = memoryKeyStore();
  });

  test('will not make a device before it holds an identity', async () => {
    const vault = createDeviceVault(store);

    await expect(vault.prepareDevice(1, 1)).rejects.toBeInstanceOf(NoIdentityError);
    await expect(vault.commitDevice(5, 12)).rejects.toBeInstanceOf(NoIdentityError);
    await expect(vault.wrapForRecovery(generateRecoveryKey())).rejects.toBeInstanceOf(NoIdentityError);
  });

  test('keeps nothing until the server has accepted the device, then keeps the device and its key packages', async () => {
    const vault = createDeviceVault(store);
    await vault.createIdentity();

    const prepared = await vault.prepareDevice(9, 2);
    expect(prepared.keyPackages.map((keyPackage) => keyPackage.lastResort)).toEqual([false, false, true]);
    expect(await readLocalDevice(store)).toBeNull();

    const committed = await vault.commitDevice(31, 12);
    expect(committed).toEqual({ userId: 9, deviceId: 31, signatureKey: prepared.signatureKey, boundSessionId: 12 });
    expect(await store.get<LocalDevice>(DEVICE_INFO)).toEqual(committed);
    for (const keyPackage of prepared.keyPackages) {
      expect(await store.get(`key-package:${toBase64(keyPackage.ref)}`)).toBeInstanceOf(Uint8Array);
    }
  });

  test('stores no secret in the clear', async () => {
    const vault = createDeviceVault(store);
    await vault.createIdentity();
    const prepared = await vault.prepareDevice(9, 1);
    await vault.commitDevice(31, 12);

    const stored = await store.get<{ storageKey: CryptoKey; sealed: Uint8Array; sealedIdentity: Uint8Array }>('device-secrets');
    expect(stored?.storageKey.extractable).toBe(false);
    expect(new TextDecoder().decode(stored?.sealed)).not.toContain(toBase64(prepared.signatureKey));
    expect(stored?.sealedIdentity.length).toBeGreaterThan(48);
  });

  test('a PIN set on one browser unlocks the identity on another, and only the right PIN does', async () => {
    const first = createDeviceVault(store);
    const { publicKey } = await first.createIdentity();
    const wrap = await first.wrapForPin(evaluate(await first.blindPin(7, '246810')));
    expect(await verifies(publicKey, wrap.signature, pinSignaturePayload(wrap.wrappedPrivate, wrap.verifier))).toBe(true);

    const second = createDeviceVault(memoryKeyStore());
    const proof = await second.pinProof(evaluate(await second.blindPin(7, '246810')));
    expect(toBase64(new Uint8Array(await crypto.subtle.digest('SHA-256', buffer(proof))))).toBe(toBase64(wrap.verifier));
    await second.restoreWithPin({ publicKey, wrappedPrivate: wrap.wrappedPrivate });
    await expect(second.prepareDevice(7, 1)).resolves.toBeDefined();

    const third = createDeviceVault(memoryKeyStore());
    const wrongProof = await third.pinProof(evaluate(await third.blindPin(7, '246811')));
    expect(toBase64(wrongProof)).not.toBe(toBase64(proof));
    await expect(third.restoreWithPin({ publicKey, wrappedPrivate: wrap.wrappedPrivate }))
      .rejects.toBeInstanceOf(RecoveryKeyMismatchError);
  });

  test('a PIN step taken out of order is refused', async () => {
    const vault = createDeviceVault(store);
    await vault.createIdentity();

    await expect(vault.wrapForPin(new Uint8Array(32))).rejects.toBeInstanceOf(NoPinError);
    await expect(vault.pinProof(new Uint8Array(32))).rejects.toBeInstanceOf(NoPinError);
    await expect(vault.restoreWithPin({ publicKey: new Uint8Array(32), wrappedPrivate: new Uint8Array(40) }))
      .rejects.toBeInstanceOf(NoPinError);
  });

  test('a set-up browser can change its PIN or add a recovery key after a reload, from the stored identity', async () => {
    const first = createDeviceVault(store);
    const { publicKey } = await first.createIdentity();
    await first.prepareDevice(9, 1);
    await first.commitDevice(31, 12);

    const reloaded = createDeviceVault(store);
    const wrap = await reloaded.wrapForPin(evaluate(await reloaded.blindPin(9, '111111')));
    const recovery = await reloaded.wrapForRecovery(generateRecoveryKey());

    expect(await verifies(publicKey, wrap.signature, pinSignaturePayload(wrap.wrappedPrivate, wrap.verifier))).toBe(true);
    expect(await verifies(publicKey, recovery.signature, recoverySignaturePayload(recovery.wrappedPrivate))).toBe(true);
  });

  test('the recovery key restores the identity, and no other key does', async () => {
    const recoveryKey = generateRecoveryKey();
    const first = createDeviceVault(memoryKeyStore());
    const { publicKey } = await first.createIdentity();
    const { wrappedPrivate } = await first.wrapForRecovery(recoveryKey);
    const vault = createDeviceVault(store);

    await expect(vault.restoreWithRecoveryKey(generateRecoveryKey(), { publicKey, wrappedPrivate }))
      .rejects.toBeInstanceOf(RecoveryKeyMismatchError);
    await vault.restoreWithRecoveryKey(recoveryKey, { publicKey, wrappedPrivate });
    await expect(vault.prepareDevice(3, 1)).resolves.toBeDefined();
  });

  test('tops up key packages from the stored device after a reload', async () => {
    const first = createDeviceVault(store);
    await first.createIdentity();
    await first.prepareDevice(9, 1);
    await first.commitDevice(31, 12);

    const packages = await createDeviceVault(store).prepareKeyPackages(2);

    expect(packages).toHaveLength(2);
    expect(packages.every((keyPackage) => !keyPackage.lastResort)).toBe(true);
    expect(await store.get(`key-package:${toBase64(packages[1].ref)}`)).toBeInstanceOf(Uint8Array);
  });

  test('has nothing to top up before a device is stored', async () => {
    await expect(createDeviceVault(store).prepareKeyPackages(1)).rejects.toBeInstanceOf(NoDeviceError);
  });

  test('replaces what an earlier device left behind and forgets everything on request', async () => {
    const vault = createDeviceVault(store);
    await store.put('key-package:old', new Uint8Array([1]));
    await vault.createIdentity();
    await vault.prepareDevice(9, 1);
    await vault.commitDevice(31, 12);
    expect(await store.get('key-package:old')).toBeUndefined();

    await vault.forget();
    expect(await readLocalDevice(store)).toBeNull();
    await expect(vault.prepareDevice(9, 1)).rejects.toBeInstanceOf(NoIdentityError);
  });
});
