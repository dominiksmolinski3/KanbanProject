import { cryptoApi } from '../../services/cryptoApi';

const ok = (body: unknown) => ({ ok: true, status: 200, json: () => Promise.resolve(body) });

const sent = (call: number) => {
  const [path, init] = (window.fetch as jest.Mock).mock.calls[call];
  return { path, method: init?.method ?? 'GET', body: init?.body ? JSON.parse(init.body) : undefined };
};

describe('cryptoApi', () => {
  beforeEach(() => {
    window.fetch = jest.fn().mockResolvedValue(ok({}));
  });

  test('reads the status and the identity backup', async () => {
    await cryptoApi.status();
    await cryptoApi.identityBackup();

    expect(sent(0)).toEqual({ path: '/api/crypto/status', method: 'GET', body: undefined });
    expect(sent(1)).toEqual({ path: '/api/crypto/identity/backup', method: 'GET', body: undefined });
  });

  test('sends key material as base64, creating or replacing the identity', async () => {
    const identity = { publicKey: new Uint8Array([1, 2, 3]), wrappedPrivate: new Uint8Array([255]) };

    await cryptoApi.createIdentity(identity);
    await cryptoApi.replaceIdentity(identity);

    expect(sent(0)).toEqual({ path: '/api/crypto/identity', method: 'POST', body: { publicKey: 'AQID', wrappedPrivate: '/w==' } });
    expect(sent(1)).toMatchObject({ path: '/api/crypto/identity', method: 'PUT' });
  });

  test('registers a device with its session, signature and key packages', async () => {
    await cryptoApi.registerDevice(12, {
      signatureKey: new Uint8Array([1]),
      credential: new Uint8Array([2]),
      identitySignature: new Uint8Array([3]),
      keyPackages: [{ ref: new Uint8Array([4]), data: new Uint8Array([5]), lastResort: true }],
    });

    expect(sent(0)).toEqual({
      path: '/api/crypto/devices',
      method: 'POST',
      body: {
        sessionId: 12,
        signatureKey: 'AQ==',
        credential: 'Ag==',
        identitySignature: 'Aw==',
        keyPackages: [{ ref: 'BA==', data: 'BQ==', lastResort: true }],
      },
    });
  });

  test('uploads more key packages for one device', async () => {
    await cryptoApi.uploadKeyPackages(3, [{ ref: new Uint8Array([4]), data: new Uint8Array([5]), lastResort: false }]);

    expect(sent(0)).toEqual({
      path: '/api/crypto/devices/3/key-packages',
      method: 'POST',
      body: { keyPackages: [{ ref: 'BA==', data: 'BQ==', lastResort: false }] },
    });
  });
});
