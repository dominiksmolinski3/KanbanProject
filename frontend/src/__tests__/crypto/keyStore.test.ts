import { KEY_DATABASE, memoryKeyStore, readLocalDevice, saveLocalDevice, wipeLocalKeys, DEVICE_INFO } from '../../crypto/keyStore';

describe('the local key store', () => {
  test('reads nothing when this browser has no IndexedDB', async () => {
    expect(globalThis.indexedDB).toBeUndefined();
    await expect(readLocalDevice()).resolves.toBeNull();
  });

  test('reads the device this browser registered', async () => {
    const store = memoryKeyStore();
    const device = { userId: 1, deviceId: 2, signatureKey: new Uint8Array([3]), boundSessionId: 4 };
    await store.put(DEVICE_INFO, device);

    await expect(readLocalDevice(store)).resolves.toEqual(device);
    await saveLocalDevice({ ...device, boundSessionId: 5 }, store);
    await expect(readLocalDevice(store)).resolves.toEqual({ ...device, boundSessionId: 5 });
    await expect(saveLocalDevice(device)).resolves.toBeUndefined();
    await store.clear();
    await expect(readLocalDevice(store)).resolves.toBeNull();
  });

  test('wiping deletes the whole key database, and does nothing where there is none', () => {
    const factory = { deleteDatabase: jest.fn() } as unknown as IDBFactory;

    wipeLocalKeys(factory);
    expect(factory.deleteDatabase).toHaveBeenCalledWith(KEY_DATABASE);
    expect(() => wipeLocalKeys(undefined)).not.toThrow();
  });
});
