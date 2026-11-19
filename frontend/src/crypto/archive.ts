export const ARCHIVE_DATABASE = 'kanban-messaging-archive';
const STORE = 'messages';
const BY_ID = 'byId';

export interface ArchiveRecord {
  conversationId: number;
  seq: number;
  id: string;
  sealed: Uint8Array;
}

export interface Archive {
  put(record: ArchiveRecord): Promise<void>;
  byId(conversationId: number, id: string): Promise<ArchiveRecord | undefined>;
  before(conversationId: number, seq: number, limit: number): Promise<ArchiveRecord[]>;
  all(conversationId: number): Promise<ArchiveRecord[]>;
  drop(conversationId: number): Promise<void>;
  clear(): Promise<void>;
}

const ascending = (a: ArchiveRecord, b: ArchiveRecord) => a.seq - b.seq;

export function memoryArchive(): Archive {
  const records = new Map<string, ArchiveRecord>();
  const key = (conversationId: number, seq: number) => `${conversationId}:${seq}`;
  const of = (conversationId: number) =>
    [...records.values()].filter((record) => record.conversationId === conversationId).sort(ascending);
  return {
    put: async (record) => {
      records.set(key(record.conversationId, record.seq), record);
    },
    byId: async (conversationId, id) => of(conversationId).find((record) => record.id === id),
    before: async (conversationId, seq, limit) => of(conversationId).filter((record) => record.seq < seq).slice(-limit),
    all: async (conversationId) => of(conversationId),
    drop: async (conversationId) => {
      of(conversationId).forEach((record) => records.delete(key(record.conversationId, record.seq)));
    },
    clear: async () => records.clear(),
  };
}

export function indexedDbArchive(factory: IDBFactory = indexedDB): Archive {
  let database: Promise<IDBDatabase> | null = null;

  const connect = () => (database ??= new Promise<IDBDatabase>((resolve, reject) => {
    const request = factory.open(ARCHIVE_DATABASE, 1);
    request.onupgradeneeded = () => {
      const store = request.result.createObjectStore(STORE, { keyPath: ['conversationId', 'seq'] });
      store.createIndex(BY_ID, ['conversationId', 'id'], { unique: false });
    };
    request.onsuccess = () => {
      const opened = request.result;
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

  const range = (conversationId: number, below = Infinity) =>
    IDBKeyRange.bound([conversationId, -Infinity], [conversationId, below], false, true);

  return {
    put: async (record) => {
      await run('readwrite', (store) => store.put(record));
    },
    byId: (conversationId, id) =>
      run<ArchiveRecord | undefined>('readonly', (store) => store.index(BY_ID).get([conversationId, id])),
    before: async (conversationId, seq, limit) => {
      const opened = await connect();
      return new Promise<ArchiveRecord[]>((resolve, reject) => {
        const found: ArchiveRecord[] = [];
        const transaction = opened.transaction(STORE, 'readonly');
        const cursor = transaction.objectStore(STORE).openCursor(range(conversationId, seq), 'prev');
        cursor.onsuccess = () => {
          const current = cursor.result;
          if (current && found.length < limit) {
            found.push(current.value as ArchiveRecord);
            current.continue();
          }
        };
        transaction.oncomplete = () => resolve(found.reverse());
        transaction.onerror = () => reject(transaction.error);
      });
    },
    all: (conversationId) => run<ArchiveRecord[]>('readonly', (store) => store.getAll(range(conversationId))),
    drop: async (conversationId) => {
      await run('readwrite', (store) => store.delete(range(conversationId)));
    },
    clear: async () => {
      await run('readwrite', (store) => store.clear());
    },
  };
}

export function wipeArchive(factory: IDBFactory | undefined = globalThis.indexedDB): void {
  factory?.deleteDatabase(ARCHIVE_DATABASE);
}
