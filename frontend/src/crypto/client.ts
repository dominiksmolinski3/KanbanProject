import type { VaultOperation, VaultResponse, WorkerOperations } from './rpc';
import { spawnCryptoWorker } from './spawnWorker';

export class VaultError extends Error {
  constructor(name: string, message: string) {
    super(message);
    this.name = name;
  }
}

type Remote<T> = { [K in keyof T]: T[K] extends (...args: infer A) => Promise<infer R> ? (...args: A) => Promise<R> : never };

const OPERATIONS: Record<VaultOperation, true> = {
  createIdentity: true, wrapForRecovery: true, restoreWithRecoveryKey: true, blindPin: true, wrapForPin: true,
  pinProof: true, restoreWithPin: true, prepareDevice: true, commitDevice: true, prepareKeyPackages: true,
  forget: true, groupStatus: true, planChanges: true, liveDeviceIds: true, prepareCommit: true, confirmCommit: true,
  discardCommit: true, acceptWelcome: true, receiveMessages: true, encryptPayload: true, recordSent: true,
  readArchive: true, historyFor: true, credentialsOf: true, addNotices: true, dropConversation: true, pins: true,
  checkContacts: true, setVerified: true, wipeMessages: true, search: true, beginLink: true, sealIdentityForLink: true,
  completeLink: true,
};

export type WorkerClient = Remote<WorkerOperations>;

export function createVaultClient(spawn: () => Worker = spawnCryptoWorker): WorkerClient {
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

  return Object.fromEntries((Object.keys(OPERATIONS) as VaultOperation[]).map((operation) => [operation, call(operation)])) as WorkerClient;
}

let shared: WorkerClient | null = null;

export const deviceVault = (): WorkerClient => (shared ??= createVaultClient());
