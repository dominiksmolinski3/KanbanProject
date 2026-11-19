import { cryptoApi } from '../../services/cryptoApi';

const ok = (body: unknown) => ({ ok: true, status: 200, json: () => Promise.resolve(body) });

const sent = (call: number) => {
  const [path, init] = (window.fetch as jest.Mock).mock.calls[call];
  return { path, method: init?.method ?? 'GET', body: init?.body ? JSON.parse(init.body) : undefined };
};

const bytes = (...values: number[]) => new Uint8Array(values);

describe('cryptoApi', () => {
  beforeEach(() => {
    window.fetch = jest.fn().mockResolvedValue(ok({}));
  });

  test('reads the status and the recovery-key backup', async () => {
    await cryptoApi.status();
    await cryptoApi.identityBackup();

    expect(sent(0)).toEqual({ path: '/api/crypto/status', method: 'GET', body: undefined });
    expect(sent(1)).toEqual({ path: '/api/crypto/identity/backup', method: 'GET', body: undefined });
  });

  test('creates or replaces the identity as base64, with or without a recovery-key wrap', async () => {
    await cryptoApi.createIdentity(bytes(1, 2, 3), bytes(255));
    await cryptoApi.replaceIdentity(bytes(1, 2, 3), null);

    expect(sent(0)).toEqual({ path: '/api/crypto/identity', method: 'POST', body: { publicKey: 'AQID', wrappedPrivate: '/w==' } });
    expect(sent(1)).toEqual({ path: '/api/crypto/identity', method: 'PUT', body: { publicKey: 'AQID', wrappedPrivate: null } });
  });

  test('adds a recovery key and sets a PIN with their signatures', async () => {
    await cryptoApi.setRecoveryKey({ wrappedPrivate: bytes(1), signature: bytes(2) });
    await cryptoApi.setPin({ wrappedPrivate: bytes(1), verifier: bytes(3), signature: bytes(2) });

    expect(sent(0)).toEqual({ path: '/api/crypto/identity/backup', method: 'PUT', body: { wrappedPrivate: 'AQ==', signature: 'Ag==' } });
    expect(sent(1)).toEqual({ path: '/api/crypto/pin', method: 'PUT', body: { wrappedPrivate: 'AQ==', verifier: 'Aw==', signature: 'Ag==' } });
  });

  test('evaluates a blinded PIN and unlocks with the proof', async () => {
    await cryptoApi.evaluatePin(bytes(4));
    await cryptoApi.unlockWithPin(bytes(5));

    expect(sent(0)).toEqual({ path: '/api/crypto/pin/evaluate', method: 'POST', body: { blinded: 'BA==' } });
    expect(sent(1)).toEqual({ path: '/api/crypto/pin/unlock', method: 'POST', body: { proof: 'BQ==' } });
  });

  test('registers a device with its session, signature and key packages', async () => {
    await cryptoApi.registerDevice(12, {
      signatureKey: bytes(1),
      credential: bytes(2),
      identitySignature: bytes(3),
      keyPackages: [{ ref: bytes(4), data: bytes(5), lastResort: true }],
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

  test('attaches, forgets and refills one device', async () => {
    await cryptoApi.attachSession(3, 20);
    await cryptoApi.forgetDevice(3);
    await cryptoApi.uploadKeyPackages(3, [{ ref: bytes(4), data: bytes(5), lastResort: false }]);

    expect(sent(0)).toEqual({ path: '/api/crypto/devices/3/session', method: 'POST', body: { sessionId: 20 } });
    expect(sent(1)).toEqual({ path: '/api/crypto/devices/3', method: 'DELETE', body: undefined });
    expect(sent(2)).toEqual({
      path: '/api/crypto/devices/3/key-packages',
      method: 'POST',
      body: { keyPackages: [{ ref: 'BA==', data: 'BQ==', lastResort: false }] },
    });
  });
});
