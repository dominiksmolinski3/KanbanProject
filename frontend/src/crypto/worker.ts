import { createDeviceVault } from './deviceVault';
import { indexedDbKeyStore } from './keyStore';
import { answer, type VaultRequest } from './rpc';

interface WorkerScope {
  onmessage: ((event: MessageEvent<VaultRequest>) => void) | null;
  postMessage(message: unknown): void;
}

const scope = self as unknown as WorkerScope;
const vault = createDeviceVault(indexedDbKeyStore());

scope.onmessage = async (event) => {
  scope.postMessage(await answer(vault, event.data));
};
