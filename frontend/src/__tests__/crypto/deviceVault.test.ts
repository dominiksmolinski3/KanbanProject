/**
 * @jest-environment node
 */
import { createDeviceVault, NoDeviceError, NoIdentityError } from '../../crypto/deviceVault';
import { DEVICE_INFO, memoryKeyStore, readLocalDevice, type KeyStore, type LocalDevice } from '../../crypto/keyStore';
import { generateRecoveryKey } from '../../crypto/recoveryKey';
import { RecoveryKeyMismatchError } from '../../crypto/keys';
import { toBase64 } from '../../crypto/bytes';

describe('the device vault', () => {
  let store: KeyStore;

  beforeEach(() => {
    store = memoryKeyStore();
  });

  test('will not make a device before it holds an identity', async () => {
    const vault = createDeviceVault(store);

    await expect(vault.prepareDevice(1, 1)).rejects.toBeInstanceOf(NoIdentityError);
    await expect(vault.commitDevice(5)).rejects.toBeInstanceOf(NoIdentityError);
  });

  test('keeps nothing until the server has accepted the device, then keeps the device and its key packages', async () => {
    const vault = createDeviceVault(store);
    await vault.createIdentity(generateRecoveryKey());

    const prepared = await vault.prepareDevice(9, 2);
    expect(prepared.keyPackages.map((keyPackage) => keyPackage.lastResort)).toEqual([false, false, true]);
    expect(await readLocalDevice(store)).toBeNull();

    const committed = await vault.commitDevice(31);
    expect(committed).toEqual({ userId: 9, deviceId: 31, signatureKey: prepared.signatureKey });
    expect(await store.get<LocalDevice>(DEVICE_INFO)).toEqual(committed);
    for (const keyPackage of prepared.keyPackages) {
      expect(await store.get(`key-package:${toBase64(keyPackage.ref)}`)).toBeInstanceOf(Uint8Array);
    }
  });

  test('stores no device secret in the clear', async () => {
    const vault = createDeviceVault(store);
    await vault.createIdentity(generateRecoveryKey());
    const prepared = await vault.prepareDevice(9, 1);
    await vault.commitDevice(31);

    const stored = await store.get<{ identity: CryptoKey; storageKey: CryptoKey; sealed: Uint8Array }>('device-secrets');
    expect(stored?.identity.extractable).toBe(false);
    expect(stored?.storageKey.extractable).toBe(false);
    expect(new TextDecoder().decode(stored?.sealed)).not.toContain(toBase64(prepared.signatureKey));
  });

  test('tops up key packages from the stored device after a reload', async () => {
    const first = createDeviceVault(store);
    await first.createIdentity(generateRecoveryKey());
    await first.prepareDevice(9, 1);
    await first.commitDevice(31);

    const reloaded = createDeviceVault(store);
    const packages = await reloaded.prepareKeyPackages(2);

    expect(packages).toHaveLength(2);
    expect(packages.every((keyPackage) => !keyPackage.lastResort)).toBe(true);
    expect(await store.get(`key-package:${toBase64(packages[1].ref)}`)).toBeInstanceOf(Uint8Array);
  });

  test('has nothing to top up before a device is stored', async () => {
    await expect(createDeviceVault(store).prepareKeyPackages(1)).rejects.toBeInstanceOf(NoDeviceError);
  });

  test('restores the identity only with the right recovery key', async () => {
    const recoveryKey = generateRecoveryKey();
    const wrapped = await createDeviceVault(memoryKeyStore()).createIdentity(recoveryKey);
    const vault = createDeviceVault(store);

    await expect(vault.restoreIdentity(generateRecoveryKey(), wrapped)).rejects.toBeInstanceOf(RecoveryKeyMismatchError);
    await vault.restoreIdentity(recoveryKey, wrapped);
    await expect(vault.prepareDevice(3, 1)).resolves.toBeDefined();
  });

  test('replaces what an earlier device left behind and forgets everything on request', async () => {
    const vault = createDeviceVault(store);
    await store.put('key-package:old', new Uint8Array([1]));
    await vault.createIdentity(generateRecoveryKey());
    await vault.prepareDevice(9, 1);
    await vault.commitDevice(31);
    expect(await store.get('key-package:old')).toBeUndefined();

    await vault.forget();
    expect(await readLocalDevice(store)).toBeNull();
    await expect(vault.prepareDevice(9, 1)).rejects.toBeInstanceOf(NoIdentityError);
  });
});
