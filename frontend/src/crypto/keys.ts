import {
  defaultCapabilities,
  encodeMlsMessage,
  generateKeyPackageWithKey,
  getCiphersuiteFromName,
  getCiphersuiteImpl,
  type Capabilities,
  type CiphersuiteImpl,
  type Credential,
} from 'ts-mls';
import { makeKeyPackageRef } from 'ts-mls/keyPackage.js';
import { buffer, concat, randomBytes, toHex, utf8 } from './bytes';

export const CIPHERSUITE = 'MLS_128_DHKEMX25519_AES128GCM_SHA256_Ed25519';

export const GROUP_NAME_EXTENSION = 0xf0a1;

// These three must match IdentitySignatures on the server, byte for byte.
export const DEVICE_SIGNATURE_LABEL = 'KanbanProject messaging device v1';
export const PIN_SIGNATURE_LABEL = 'KanbanProject messaging PIN v1';
export const RECOVERY_SIGNATURE_LABEL = 'KanbanProject messaging recovery v1';

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

export const messagingCapabilities = (): Capabilities => {
  const capabilities = defaultCapabilities();
  return { ...capabilities, extensions: [...capabilities.extensions, GROUP_NAME_EXTENSION] };
};

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

export async function recoveryWrappingKey(recoveryKey: string): Promise<CryptoKey> {
  const material = await crypto.subtle.importKey('raw', buffer(utf8(recoveryKey)), 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: buffer(RECOVERY_SALT), info: buffer(IDENTITY_WRAP_INFO) },
    material, { name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
}

export const importIdentity = (pkcs8: Uint8Array): Promise<CryptoKey> =>
  crypto.subtle.importKey('pkcs8', buffer(pkcs8), 'Ed25519', false, ['sign']);

export async function createIdentity(): Promise<{ publicKey: Uint8Array; pkcs8: Uint8Array }> {
  const pair = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']) as CryptoKeyPair;
  return {
    publicKey: new Uint8Array(await crypto.subtle.exportKey('raw', pair.publicKey)),
    pkcs8: new Uint8Array(await crypto.subtle.exportKey('pkcs8', pair.privateKey)),
  };
}

export const wrapIdentity = (wrappingKey: CryptoKey, publicKey: Uint8Array, pkcs8: Uint8Array): Promise<Uint8Array> =>
  seal(wrappingKey, pkcs8, publicKey);

export async function openIdentity(wrappingKey: CryptoKey, identity: WrappedIdentity): Promise<Uint8Array> {
  try {
    const pkcs8 = await open(wrappingKey, identity.wrappedPrivate, identity.publicKey);
    await importIdentity(pkcs8);
    return pkcs8;
  } catch {
    throw new RecoveryKeyMismatchError();
  }
}

export async function signWithIdentity(pkcs8: Uint8Array, payload: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.sign('Ed25519', await importIdentity(pkcs8), buffer(payload)));
}

export const pinSignaturePayload = (wrappedPrivate: Uint8Array, verifier: Uint8Array): Uint8Array =>
  concat(utf8(PIN_SIGNATURE_LABEL), wrappedPrivate, verifier);

export const recoverySignaturePayload = (wrappedPrivate: Uint8Array): Uint8Array =>
  concat(utf8(RECOVERY_SIGNATURE_LABEL), wrappedPrivate);

export const deviceSignaturePayload = (signatureKey: Uint8Array, credential: Uint8Array): Uint8Array =>
  concat(utf8(DEVICE_SIGNATURE_LABEL), signatureKey, credential);

export async function createDevice(userId: number, identity: Uint8Array):
  Promise<{ secrets: DeviceSecrets; identitySignature: Uint8Array }> {
  const impl = await ciphersuite();
  const { signKey, publicKey } = await impl.signature.keygen();
  const credential = utf8(`u${userId}.${toHex(randomBytes(16))}`);
  return {
    secrets: { signKey, signatureKey: publicKey, credential },
    identitySignature: await signWithIdentity(identity, deviceSignaturePayload(publicKey, credential)),
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
      credential, messagingCapabilities(), lifetime, [], { signKey: device.signKey, publicKey: device.signatureKey }, impl);
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
