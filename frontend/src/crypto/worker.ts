import { indexedDbArchive } from './archive';
import { createDeviceVault } from './deviceVault';
import { createMessagingEngine } from './engine';
import { indexedDbKeyStore } from './keyStore';
import { answer, type VaultRequest } from './rpc';

interface WorkerScope {
  onmessage: ((event: MessageEvent<VaultRequest>) => void) | null;
  postMessage(message: unknown): void;
}

const scope = self as unknown as WorkerScope;
const store = indexedDbKeyStore();
const vault = createDeviceVault(store);
const engine = createMessagingEngine(store, indexedDbArchive());
const operations = {
  ...vault,
  ...engine,
  forget: async () => {
    await vault.forget();
    await engine.wipeMessages();
  },
};

scope.onmessage = async (event) => {
  scope.postMessage(await answer(operations, event.data));
};
