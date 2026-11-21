import { buffer, fromBase64, toBase64, utf8 } from './bytes';

const CURVE = { name: 'ECDH', namedCurve: 'P-256' } as const;
const INFO = 'KanbanProject device link v1';
const LINK_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const PUBLIC_KEY_BYTES = 65;
const SECRET_BYTES = 16;

const toUrl = (bytes: Uint8Array) => toBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

const fromUrl = (text: string) => {
  const padded = text.replace(/-/g, '+').replace(/_/g, '/');
  return fromBase64(padded + '='.repeat((4 - (padded.length % 4)) % 4));
};

export interface LinkCode {
  id: string;
  devicePublic: Uint8Array;
  secret: Uint8Array;
}

export const makeLinkCode = (id: string, devicePublic: Uint8Array, secret: Uint8Array): string =>
  `${id}.${toUrl(devicePublic)}.${toUrl(secret)}`;

export function parseLinkCode(input: string): LinkCode | null {
  const trimmed = input.trim();
  const code = trimmed.includes('#') ? trimmed.slice(trimmed.lastIndexOf('#') + 1) : trimmed;
  const [id, key, secretText, ...rest] = code.split('.');
  const urlSafe = /^[A-Za-z0-9_-]+$/;
  if (rest.length || !id || !key || !secretText || !LINK_ID.test(id) || !urlSafe.test(key) || !urlSafe.test(secretText)) {
    return null;
  }
  try {
    const devicePublic = fromUrl(key);
    const secret = fromUrl(secretText);
    return devicePublic.length === PUBLIC_KEY_BYTES && devicePublic[0] === 4 && secret.length === SECRET_BYTES
      ? { id, devicePublic, secret } : null;
  } catch {
    return null;
  }
}

export interface LinkKeyPair {
  privateKey: CryptoKey;
  publicKey: Uint8Array;
}

export async function newLinkKeyPair(): Promise<LinkKeyPair> {
  const pair = await crypto.subtle.generateKey(CURVE, false, ['deriveBits']);
  return { privateKey: pair.privateKey, publicKey: new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)) };
}

export const newLinkSecret = (): Uint8Array => crypto.getRandomValues(new Uint8Array(SECRET_BYTES));

async function linkKey(privateKey: CryptoKey, peerPublic: Uint8Array, linkId: string, secret: Uint8Array,
  usage: KeyUsage): Promise<CryptoKey> {
  const peer = await crypto.subtle.importKey('raw', buffer(peerPublic), CURVE, false, []);
  const shared = await crypto.subtle.deriveBits({ name: 'ECDH', public: peer }, privateKey, 256);
  const base = await crypto.subtle.importKey('raw', shared, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: buffer(secret), info: buffer(utf8(`${INFO} ${linkId}`)) },
    base, { name: 'AES-GCM', length: 256 }, false, [usage]);
}

export interface SealedIdentity {
  linkerPublic: Uint8Array;
  sealed: Uint8Array;
}

export async function sealIdentity(code: LinkCode, identityPublic: Uint8Array, pkcs8: Uint8Array): Promise<SealedIdentity> {
  const linkId = code.id;
  const ephemeral = await newLinkKeyPair();
  const key = await linkKey(ephemeral.privateKey, code.devicePublic, linkId, code.secret, 'encrypt');
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const plain = utf8(JSON.stringify({ publicKey: toBase64(identityPublic), pkcs8: toBase64(pkcs8) }));
  const encrypted = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: buffer(iv), additionalData: buffer(utf8(linkId)) }, key,
    buffer(plain)));
  const sealed = new Uint8Array(iv.length + encrypted.length);
  sealed.set(iv);
  sealed.set(encrypted, iv.length);
  return { linkerPublic: ephemeral.publicKey, sealed };
}

export interface LinkedIdentity {
  publicKey: Uint8Array;
  pkcs8: Uint8Array;
}

export async function openIdentity(linkId: string, own: LinkKeyPair, secret: Uint8Array, linkerPublic: Uint8Array,
  sealed: Uint8Array): Promise<LinkedIdentity> {
  if (sealed.length <= 12) throw new Error('The link answer is truncated');
  const key = await linkKey(own.privateKey, linkerPublic, linkId, secret, 'decrypt');
  const plain = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: buffer(sealed.subarray(0, 12)), additionalData: buffer(utf8(linkId)) }, key, buffer(sealed.subarray(12)));
  const parsed = JSON.parse(new TextDecoder().decode(plain));
  return { publicKey: fromBase64(parsed.publicKey), pkcs8: fromBase64(parsed.pkcs8) };
}
