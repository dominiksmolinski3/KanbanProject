import {
  defaultCapabilities,
  encodeMlsMessage,
  generateKeyPackageWithKey,
  getCiphersuiteFromName,
  getCiphersuiteImpl,
  type CiphersuiteImpl,
  type Credential,
} from 'ts-mls';
import { makeKeyPackageRef } from 'ts-mls/keyPackage.js';
import { buffer, concat, randomBytes, toHex, utf8 } from './bytes';

export const CIPHERSUITE = 'MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519';

// Must match DeviceSignatures.LABEL on the server, byte for byte.
export const DEVICE_SIGNATURE_LABEL = 'KanbanProject messaging device v1';

const RECOVERY_SALT = utf8('KanbanProject recovery key v1');
const IDENTITY_WRAP_INFO = utf8('identity key');
const IV_BYTES = 12;
const KEY_PACKAGE_LIFETIME_SECONDS = 90 * 24 * 60 * 60;

export class RecoveryKeyMismatchError extends Error {
  constructor() {
    super('RecoveryKeyMismatch');
    this.name = 'RecoveryKeyMismatchError';
  }
}

export interface WrappedIdentity {
  publicKey: Uint8Array;
  wrappedPrivate: Uint8Array;
}

export interface DeviceSecrets {
  signKey: Uint8Array;
  signatureKey: Uint8Array;
  credential: Uint8Array;
}

export interface KeyPackageUpload {
  ref: Uint8Array;
  data: Uint8Array;
  lastResort: boolean;
}

export interface KeyPackagePrivate {
  initPrivateKey: Uint8Array;
  hpkePrivateKey: Uint8Array;
}

export interface GeneratedKeyPackage {
  upload: KeyPackageUpload;
  secret: KeyPackagePrivate;
}

let suite: Promise<CiphersuiteImpl> | null = null;

export const ciphersuite = (): Promise<CiphersuiteImpl> =>
  (suite ??= getCiphersuiteImpl(getCiphersuiteFromName(CIPHERSUITE)));

export async function seal(key: CryptoKey, plaintext: Uint8Array, associated: Uint8Array): Promise<Uint8Array> {
  const iv = randomBytes(IV_BYTES);
  const sealed = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: buffer(iv), additionalData: buffer(associated) }, key, buffer(plaintext));
  return concat(iv, new Uint8Array(sealed));
}

export async function open(key: CryptoKey, sealed: Uint8Array, associated: Uint8Array): Promise<Uint8Array> {
  const opened = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: buffer(sealed.subarray(0, IV_BYTES)), additionalData: buffer(associated) },
    key, buffer(sealed.subarray(IV_BYTES)));
  return new Uint8Array(opened);
}

export const newStorageKey = (): Promise<CryptoKey> =>
  crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);

async function recoveryWrappingKey(recoveryKey: string): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey('raw', buffer(utf8(recoveryKey)), 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: buffer(RECOVERY_SALT), info: buffer(IDENTITY_WRAP_INFO) },
    material, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

const importIdentity = (pkcs8: Uint8Array): Promise<CryptoKey> =>
  crypto.subtle.importKey('pkcs8', buffer(pkcs8), 'Ed25519', false, ['sign']);

export async function createIdentity(recoveryKey: string): Promise<WrappedIdentity & { privateKey: CryptoKey }> {
  const pair = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']) as CryptoKeyPair;
  const publicKey = new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey));
  const pkcs8 = new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey));
  try {
    const wrappedPrivate = await seal(await recoveryWrappingKey(recoveryKey), pkcs8, publicKey);
    return { publicKey, wrappedPrivate, privateKey: await importIdentity(pkcs8) };
  } finally {
    pkcs8.fill(0);
  }
}

export async function restoreIdentity(recoveryKey: string, identity: WrappedIdentity): Promise<CryptoKey> {
  let pkcs8: Uint8Array;
  try {
    pkcs8 = await open(await recoveryWrappingKey(recoveryKey), identity.wrappedPrivate, identity.publicKey);
  } catch {
    throw new RecoveryKeyMismatchError();
  }
  try {
    return await importIdentity(pkcs8);
  } finally {
    pkcs8.fill(0);
  }
}

export const deviceSignaturePayload = (signatureKey: Uint8Array, credential: Uint8Array): Uint8Array =>
  concat(utf8(DEVICE_SIGNATURE_LABEL), signatureKey, credential);

export async function createDevice(userId: number, identity: CryptoKey):
  Promise<{ secrets: DeviceSecrets; identitySignature: Uint8Array }> {
  const impl = await ciphersuite();
  const { signKey, publicKey } = await impl.signature.keygen();
  const credential = utf8(`u${userId}.${toHex(randomBytes(16))}`);
  const signature = await crypto.subtle.sign('Ed25519', identity, buffer(deviceSignaturePayload(publicKey, credential)));
  return {
    secrets: { signKey, signatureKey: publicKey, credential },
    identitySignature: new Uint8Array(signature),
  };
}

export async function createKeyPackages(device: DeviceSecrets, count: number, lastResort: boolean, now = Date.now()):
  Promise<GeneratedKeyPackage[]> {
  const impl = await ciphersuite();
  const credential: Credential = { credentialType: 'basic', identity: device.credential };
  const seconds = Math.floor(now / 1000);
  const lifetime = { notBefore: BigInt(seconds - 3600), notAfter: BigInt(seconds + KEY_PACKAGE_LIFETIME_SECONDS) };
  const generated: GeneratedKeyPackage[] = [];
  for (let i = 0; i < count; i++) {
    const { publicPackage, privatePackage } = await generateKeyPackageWithKey(
      credential, defaultCapabilities(), lifetime, [], { signKey: device.signKey, publicKey: device.signatureKey }, impl);
    generated.push({
      upload: {
        ref: await makeKeyPackageRef(publicPackage, impl.hash),
        data: encodeMlsMessage({ keyPackage: publicPackage, wireformat: 'mls_key_package', version: 'mls10' }),
        lastResort,
      },
      secret: { initPrivateKey: privatePackage.initPrivateKey, hpkePrivateKey: privatePackage.hpkePrivateKey },
    });
  }
  return generated;
}
