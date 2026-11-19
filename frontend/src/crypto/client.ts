import type { DeviceVault } from './deviceVault';
import type { VaultOperation, VaultResponse } from './rpc';
import { spawnCryptoWorker } from './spawnWorker';

export class VaultError extends Error {
  constructor(name: string, message: string) {
    super(message);
    this.name = name;
  }
}

type Remote<T> = { [K in keyof T]: T[K] extends (...args: infer A) => Promise<infer R> ? (...args: A) => Promise<R> : never };

export function createVaultClient(spawn: () => Worker = spawnCryptoWorker): Remote<DeviceVault> {
  let worker: Worker | null = null;
  let nextId = 1;
  const waiting = new Map<number, { resolve: (value: unknown) => void; reject: (error: Error) => void }>();

  const connect = () => {
    if (!worker) {
      worker = spawn();
      worker.onmessage = (event: MessageEvent<VaultResponse>) => {
        const reply = event.data;
        const caller = waiting.get(reply.id);
        if (!caller) return;
        waiting.delete(reply.id);
        if (reply.ok) {
          caller.resolve(reply.value);
        } else {
          caller.reject(new VaultError(reply.error.name, reply.error.message));
        }
      };
    }
    return worker;
  };

  const call = (operation: VaultOperation) => (...args: unknown[]) => new Promise((resolve, reject) => {
    const id = nextId++;
    waiting.set(id, { resolve, reject });
    connect().postMessage({ id, operation, args });
  });

  return {
    createIdentity: call('createIdentity'),
    wrapForRecovery: call('wrapForRecovery'),
    restoreWithRecoveryKey: call('restoreWithRecoveryKey'),
    blindPin: call('blindPin'),
    wrapForPin: call('wrapForPin'),
    pinProof: call('pinProof'),
    restoreWithPin: call('restoreWithPin'),
    prepareDevice: call('prepareDevice'),
    commitDevice: call('commitDevice'),
    prepareKeyPackages: call('prepareKeyPackages'),
    forget: call('forget'),
  } as Remote<DeviceVault>;
}

let shared: Remote<DeviceVault> | null = null;

export const deviceVault = (): Remote<DeviceVault> => (shared ??= createVaultClient());
