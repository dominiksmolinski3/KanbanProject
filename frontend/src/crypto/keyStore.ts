import { ARCHIVE_DATABASE } from './archive';

export const KEY_DATABASE = 'kanban-messaging-keys';
const STORE = 'keys';

export const DEVICE_INFO = 'device-info';

export interface LocalDevice {
  userId: number;
  deviceId: number;
  signatureKey: Uint8Array;
  boundSessionId: number;
}

export interface KeyStore {
  get<T>(key: string): Promise<T | undefined>;
  put(key: string, value: unknown): Promise<void>;
  update<T>(key: string, change: (current: T | undefined) => T | undefined): Promise<void>;
  clear(): Promise<void>;
}

export function memoryKeyStore(): KeyStore {
  const values = new Map<string, unknown>();
  return {
    get: async <T>(key: string) => values.get(key) as T | undefined,
    put: async (key, value) => {
      values.set(key, value);
    },
    update: async <T>(key: string, change: (current: T | undefined) => T | undefined) => {
      const next = change(values.get(key) as T | undefined);
      if (next !== undefined) values.set(key, next);
    },
    clear: async () => values.clear(),
  };
}

export function indexedDbKeyStore(factory: IDBFactory = indexedDB): KeyStore {
  let database: Promise<IDBDatabase> | null = null;

  const connect = () => (database ??= new Promise<IDBDatabase>((resolve, reject) => {
    const request = factory.open(KEY_DATABASE, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE);
    request.onsuccess = () => {
      const opened = request.result;
      // Sign-out deletes the database from the page; an open connection would block that.
      opened.onversionchange = () => {
        opened.close();
        database = null;
      };
      resolve(opened);
    };
    request.onerror = () => {
      database = null;
      reject(request.error);
    };
  }));

  const run = async <T>(mode: IDBTransactionMode, operation: (store: IDBObjectStore) => IDBRequest<T>) => {
    const opened = await connect();
    return new Promise<T>((resolve, reject) => {
      const transaction = opened.transaction(STORE, mode);
      const request = operation(transaction.objectStore(STORE));
      transaction.oncomplete = () => resolve(request.result);
      transaction.onerror = () => reject(transaction.error);
      transaction.onabort = () => reject(transaction.error);
    });
  };

  return {
    get: <T>(key: string) => run<T | undefined>('readonly', (store) => store.get(key)),
    put: async (key, value) => {
      await run('readwrite', (store) => store.put(value, key));
    },
    update: async <T>(key: string, change: (current: T | undefined) => T | undefined) => {
      const opened = await connect();
      await new Promise<void>((resolve, reject) => {
        const transaction = opened.transaction(STORE, 'readwrite');
        const store = transaction.objectStore(STORE);
        const read = store.get(key);
        read.onsuccess = () => {
          const next = change(read.result as T | undefined);
          if (next !== undefined) store.put(next, key);
        };
        transaction.oncomplete = () => resolve();
        transaction.onerror = () => reject(transaction.error);
        transaction.onabort = () => reject(transaction.error);
      });
    },
    clear: async () => {
      await run('readwrite', (store) => store.clear());
    },
  };
}

let pageStore: KeyStore | null = null;

const pageKeyStore = (store?: KeyStore): KeyStore | null =>
  store ?? (globalThis.indexedDB ? (pageStore ??= indexedDbKeyStore()) : null);

export async function readLocalDevice(store?: KeyStore): Promise<LocalDevice | null> {
  const source = pageKeyStore(store);
  return source ? (await source.get<LocalDevice>(DEVICE_INFO)) ?? null : null;
}

export async function saveLocalDevice(device: LocalDevice, store?: KeyStore): Promise<void> {
  await pageKeyStore(store)?.put(DEVICE_INFO, device);
}

// One transaction: a device that was deleted since it was read must not be written back.
export async function rebindLocalDevice(sessionId: number, store?: KeyStore): Promise<void> {
  await pageKeyStore(store)?.update<LocalDevice>(DEVICE_INFO,
    (current) => (current ? { ...current, boundSessionId: sessionId } : undefined));
}

export function wipeLocalKeys(factory: IDBFactory | undefined = globalThis.indexedDB): void {
  factory?.deleteDatabase(KEY_DATABASE);
  factory?.deleteDatabase(ARCHIVE_DATABASE);
}
