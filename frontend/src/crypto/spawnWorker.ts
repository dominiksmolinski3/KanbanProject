export const spawnCryptoWorker = (): Worker =>
  new Worker(new URL('./worker.ts', import.meta.url), { type: 'module', name: 'messaging-keys' });
