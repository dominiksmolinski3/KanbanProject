import { createVaultClient, VaultError } from '../../crypto/client';
import { answer, type VaultRequest } from '../../crypto/rpc';
import type { DeviceVault } from '../../crypto/deviceVault';

class FakeWorker {
  onmessage: ((event: MessageEvent) => void) | null = null;
  posted: VaultRequest[] = [];

  constructor(private readonly vault: Partial<DeviceVault>) {}

  postMessage(request: VaultRequest) {
    this.posted.push(request);
    void answer(this.vault as DeviceVault, request).then((reply) => this.onmessage?.({ data: reply } as MessageEvent));
  }
}

describe('the vault client', () => {
  test('starts the worker on first use and hands each call its own answer', async () => {
    const vault = {
      createIdentity: jest.fn(async () => ({ publicKey: new Uint8Array([3]) })),
      commitDevice: jest.fn(async (deviceId: number, boundSessionId: number) => ({ userId: 1, deviceId, signatureKey: new Uint8Array(), boundSessionId })),
    };
    const worker = new FakeWorker(vault);
    const spawn = jest.fn(() => worker as unknown as Worker);
    const client = createVaultClient(spawn);

    expect(spawn).not.toHaveBeenCalled();
    const [identity, device] = await Promise.all([client.createIdentity(), client.commitDevice(8, 12)]);

    expect(spawn).toHaveBeenCalledTimes(1);
    expect(identity.publicKey).toEqual(new Uint8Array([3]));
    expect(device.deviceId).toBe(8);
    expect(worker.posted.map((request) => request.operation)).toEqual(['createIdentity', 'commitDevice']);
  });

  test('rejects with the error the worker reported, under the same name', async () => {
    const failure = new Error('RecoveryKeyMismatch');
    failure.name = 'RecoveryKeyMismatchError';
    const client = createVaultClient(() => new FakeWorker({
      restoreWithRecoveryKey: jest.fn(async () => { throw failure; }),
    }) as unknown as Worker);

    const outcome = client.restoreWithRecoveryKey('key', { publicKey: new Uint8Array(), wrappedPrivate: new Uint8Array() });

    await expect(outcome).rejects.toBeInstanceOf(VaultError);
    await expect(outcome).rejects.toMatchObject({ name: 'RecoveryKeyMismatchError' });
  });

  test('ignores a reply nobody is waiting for', async () => {
    const worker = new FakeWorker({ forget: jest.fn(async () => undefined) });
    const client = createVaultClient(() => worker as unknown as Worker);
    await client.forget();

    expect(() => worker.onmessage?.({ data: { id: 999, ok: true, value: 1 } } as MessageEvent)).not.toThrow();
  });
});

describe('answering a request inside the worker', () => {
  test('refuses an operation the vault does not have', async () => {
    const reply = await answer({} as DeviceVault, { id: 1, operation: 'nothing' as never, args: [] });

    expect(reply).toEqual({ id: 1, ok: false, error: { name: 'Error', message: 'Unknown operation nothing' } });
  });
});
