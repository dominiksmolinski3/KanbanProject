import { concat, fromBase64, toBase64, utf8 } from './bytes';

export const MAX_TEXT_LENGTH = 4000;
export const MAX_TTL_SECONDS = 365 * 24 * 60 * 60;
export const MAX_MENTIONS = 50;
export const MAX_FILES = 10;

const BUCKETS = [256, 1024, 4096, 16384];

const ENVELOPE_LABEL = utf8('KanbanProject message v1');

export interface HistoryItem {
  seq: number;
  id: string;
  senderId: number | null;
  sentAt: string;
  text: string;
  replyTo: string | null;
  editedAt: string | null;
  deleted: boolean;
  reactions: Record<string, number[]>;
  threadId?: string | null;
  mentions?: number[];
  expiresAt?: string | null;
  files?: FileRef[];
}

export interface FileRef {
  blobId: number;
  key: string;
  name: string;
  type: string;
  size: number;
}

export type Payload =
  | { t: 'text'; id: string; text: string; replyTo?: string | null; thread?: string | null; mentions?: number[];
    ttl?: number; files?: FileRef[] }
  | { t: 'edit'; id: string; target: string; text: string }
  | { t: 'delete'; id: string; target: string }
  | { t: 'react'; id: string; target: string; emoji: string; on: boolean }
  | { t: 'history'; id: string; for: string[]; items: HistoryItem[] };

export interface Envelope {
  payload: Payload;
  credential: string;
  signature: Uint8Array;
}

export class PayloadError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'PayloadError';
  }
}

export const signedBytes = (groupId: Uint8Array, payload: Payload): Uint8Array =>
  concat(ENVELOPE_LABEL, groupId, utf8(JSON.stringify(payload)));

export function bucketFor(length: number): number {
  const bucket = BUCKETS.find((size) => length <= size);
  return bucket ?? Math.ceil(length / BUCKETS[BUCKETS.length - 1]) * BUCKETS[BUCKETS.length - 1];
}

export function encodeEnvelope(envelope: Envelope): Uint8Array {
  const json = utf8(JSON.stringify({ p: envelope.payload, c: envelope.credential, s: toBase64(envelope.signature) }));
  const padded = new Uint8Array(bucketFor(json.length));
  padded.set(json);
  return padded;
}

const isString = (value: unknown): value is string => typeof value === 'string';

function validFile(value: unknown): value is FileRef {
  if (!value || typeof value !== 'object') return false;
  const file = value as Record<string, unknown>;
  return Number.isInteger(file.blobId) && isString(file.key) && file.key.length <= 64
    && isString(file.name) && file.name.length <= 255 && isString(file.type) && file.type.length <= 127
    && Number.isInteger(file.size) && (file.size as number) >= 0;
}

function validPayload(value: unknown): value is Payload {
  if (!value || typeof value !== 'object') return false;
  const payload = value as Record<string, unknown>;
  if (!isString(payload.id) || payload.id.length > 64) return false;
  switch (payload.t) {
    case 'text':
      return isString(payload.text) && payload.text.length <= MAX_TEXT_LENGTH
        && (payload.replyTo === undefined || payload.replyTo === null || isString(payload.replyTo))
        && (payload.thread === undefined || payload.thread === null || isString(payload.thread))
        && (payload.mentions === undefined || (Array.isArray(payload.mentions) && payload.mentions.length <= MAX_MENTIONS
          && payload.mentions.every(Number.isInteger)))
        && (payload.ttl === undefined || (Number.isInteger(payload.ttl) && (payload.ttl as number) > 0
          && (payload.ttl as number) <= MAX_TTL_SECONDS))
        && (payload.files === undefined || (Array.isArray(payload.files) && payload.files.length <= MAX_FILES
          && payload.files.every(validFile)));
    case 'edit':
      return isString(payload.target) && isString(payload.text) && payload.text.length <= MAX_TEXT_LENGTH;
    case 'delete':
      return isString(payload.target);
    case 'react':
      return isString(payload.target) && isString(payload.emoji) && payload.emoji.length <= 16
        && typeof payload.on === 'boolean';
    case 'history':
      return Array.isArray(payload.for) && payload.for.every(isString) && Array.isArray(payload.items);
    default:
      return false;
  }
}

export function decodeEnvelope(bytes: Uint8Array): Envelope {
  let end = bytes.length;
  while (end > 0 && bytes[end - 1] === 0) end--;
  let parsed: { p?: unknown; c?: unknown; s?: unknown };
  try {
    parsed = JSON.parse(new TextDecoder().decode(bytes.subarray(0, end)));
  } catch {
    throw new PayloadError('Unreadable message');
  }
  if (!validPayload(parsed.p) || !isString(parsed.c) || !isString(parsed.s)) {
    throw new PayloadError('Malformed message');
  }
  return { payload: parsed.p, credential: parsed.c, signature: fromBase64(parsed.s) };
}

export const newMessageId = (): string => crypto.randomUUID();
