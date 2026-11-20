import { fromBase64, toBase64, utf8 } from './bytes';
import { ONE_TIME_PACKAGES } from './constants';
import {
  createDevice,
  createIdentity,
  createKeyPackages,
  newStorageKey,
  open,
  openIdentity,
  pinSignaturePayload,
  recoverySignaturePayload,
  recoveryWrappingKey,
  seal,
  signWithIdentity,
  wrapIdentity,
  type DeviceSecrets,
  type GeneratedKeyPackage,
  type KeyPackageUpload,
  type WrappedIdentity,
} from './keys';
import { DEVICE_INFO, type KeyStore, type LocalDevice } from './keyStore';
import { blindPin, pinKeys, proofVerifier, unblindPin, type PinKeys } from './pin';
import {
  newLinkKeyPair,
  newLinkSecret,
  openIdentity as openLinkedIdentity,
  sealIdentity,
  type LinkCode,
  type LinkKeyPair,
  type SealedIdentity,
} from './linking';

const DEVICE_SECRETS = 'device-secrets';
const SECRETS_LABEL = utf8('device secrets');
const IDENTITY_LABEL = utf8('identity key');
const PACKAGE_LABEL = utf8('key package');

export interface StoredSecrets {
  storageKey: CryptoKey;
  sealed: Uint8Array;
  identityPublic: Uint8Array;
  sealedIdentity: Uint8Array;
}

interface Identity {
  publicKey: Uint8Array;
  pkcs8: Uint8Array;
}

export interface PreparedDevice {
  signatureKey: Uint8Array;
  credential: Uint8Array;
  identitySignature: Uint8Array;
  keyPackages: KeyPackageUpload[];
}

export interface SignedWrap {
  wrappedPrivate: Uint8Array;
  signature: Uint8Array;
}

export interface PinWrap extends SignedWrap {
  verifier: Uint8Array;
}

export class NoIdentityError extends Error {
  constructor() {
    super('NoIdentity');
    this.name = 'NoIdentityError';
  }
}

export class NoDeviceError extends Error {
  constructor() {
    super('NoDevice');
    this.name = 'NoDeviceError';
  }
}

export class NoLinkError extends Error {
  constructor() {
    super('NoLink');
    this.name = 'NoLinkError';
  }
}

export class NoPinError extends Error {
  constructor() {
    super('NoPin');
    this.name = 'NoPinError';
  }
}

export interface OpenedDevice {
  stored: StoredSecrets;
  secrets: DeviceSecrets;
}

export interface StoredKeyPackage {
  initPrivateKey: Uint8Array;
  hpkePrivateKey: Uint8Array;
  data: Uint8Array;
  lastResort: boolean;
}

export const keyPackageKey = (ref: Uint8Array): string => `key-package:${toBase64(ref)}`;

export async function openStoredDevice(store: KeyStore): Promise<OpenedDevice> {
  const stored = await store.get<StoredSecrets>(DEVICE_SECRETS);
  if (!stored) {
    throw new NoDeviceError();
  }
  const plain = JSON.parse(new TextDecoder().decode(await open(stored.storageKey, stored.sealed, SECRETS_LABEL)));
  return {
    stored,
    secrets: {
      signKey: fromBase64(plain.signKey),
      signatureKey: fromBase64(plain.signatureKey),
      credential: fromBase64(plain.credential),
    },
  };
}

export async function openKeyPackage(store: KeyStore, storageKey: CryptoKey, ref: Uint8Array):
  Promise<StoredKeyPackage | null> {
  const sealed = await store.get<Uint8Array>(keyPackageKey(ref));
  if (!sealed) {
    return null;
  }
  const plain = JSON.parse(new TextDecoder().decode(await open(storageKey, sealed, PACKAGE_LABEL)));
  if (!plain.data) {
    return null;
  }
  return {
    initPrivateKey: fromBase64(plain.initPrivateKey),
    hpkePrivateKey: fromBase64(plain.hpkePrivateKey),
    data: fromBase64(plain.data),
    lastResort: Boolean(plain.lastResort),
  };
}

export function createDeviceVault(store: KeyStore) {
  let identity: Identity | null = null;
  let pending: { userId: number; secrets: DeviceSecrets; packages: GeneratedKeyPackage[] } | null = null;
  let blind: bigint | null = null;
  let unlockKeys: PinKeys | null = null;
  let link: { pair: LinkKeyPair; secret: Uint8Array } | null = null;

  const keepPackages = async (storageKey: CryptoKey, packages: GeneratedKeyPackage[]) => {
    for (const { upload, secret } of packages) {
      const sealed = await seal(storageKey, utf8(JSON.stringify({
        initPrivateKey: toBase64(secret.initPrivateKey),
        hpkePrivateKey: toBase64(secret.hpkePrivateKey),
        data: toBase64(upload.data),
        lastResort: upload.lastResort,
      })), PACKAGE_LABEL);
      await store.put(keyPackageKey(upload.ref), sealed);
    }
  };

  const storedDevice = () => openStoredDevice(store);

  const heldIdentity = async (): Promise<Identity> => {
    if (identity) {
      return identity;
    }
    const stored = await store.get<StoredSecrets>(DEVICE_SECRETS);
    if (!stored) {
      throw new NoIdentityError();
    }
    identity = {
      publicKey: stored.identityPublic,
      pkcs8: await open(stored.storageKey, stored.sealedIdentity, IDENTITY_LABEL),
    };
    return identity;
  };

  const takeBlind = (): bigint => {
    if (blind === null) {
      throw new NoPinError();
    }
    const value = blind;
    blind = null;
    return value;
  };

  return {
    async createIdentity(): Promise<{ publicKey: Uint8Array }> {
      identity = await createIdentity();
      return { publicKey: identity.publicKey };
    },

    async wrapForRecovery(recoveryKey: string): Promise<SignedWrap> {
      const held = await heldIdentity();
      const wrappedPrivate = await wrapIdentity(await recoveryWrappingKey(recoveryKey), held.publicKey, held.pkcs8);
      return { wrappedPrivate, signature: await signWithIdentity(held.pkcs8, recoverySignaturePayload(wrappedPrivate)) };
    },

    async restoreWithRecoveryKey(recoveryKey: string, wrapped: WrappedIdentity): Promise<void> {
      identity = {
        publicKey: wrapped.publicKey,
        pkcs8: await openIdentity(await recoveryWrappingKey(recoveryKey), wrapped),
      };
    },

    async blindPin(userId: number, pin: string): Promise<Uint8Array> {
      const blinded = blindPin(userId, pin);
      blind = blinded.blind;
      return blinded.blinded;
    },

    async wrapForPin(evaluated: Uint8Array): Promise<PinWrap> {
      const held = await heldIdentity();
      const keys = await pinKeys(unblindPin(evaluated, takeBlind()));
      const wrappedPrivate = await wrapIdentity(keys.wrappingKey, held.publicKey, held.pkcs8);
      const verifier = await proofVerifier(keys.proof);
      return {
        wrappedPrivate,
        verifier,
        signature: await signWithIdentity(held.pkcs8, pinSignaturePayload(wrappedPrivate, verifier)),
      };
    },

    async pinProof(evaluated: Uint8Array): Promise<Uint8Array> {
      unlockKeys = await pinKeys(unblindPin(evaluated, takeBlind()));
      return unlockKeys.proof;
    },

    async restoreWithPin(wrapped: WrappedIdentity): Promise<void> {
      if (!unlockKeys) {
        throw new NoPinError();
      }
      identity = { publicKey: wrapped.publicKey, pkcs8: await openIdentity(unlockKeys.wrappingKey, wrapped) };
      unlockKeys = null;
    },

    async beginLink(): Promise<{ publicKey: Uint8Array; secret: Uint8Array }> {
      link = { pair: await newLinkKeyPair(), secret: newLinkSecret() };
      return { publicKey: link.pair.publicKey, secret: link.secret };
    },

    async sealIdentityForLink(code: LinkCode): Promise<SealedIdentity> {
      const held = await heldIdentity();
      return sealIdentity(code, held.publicKey, held.pkcs8);
    },

    async completeLink(linkId: string, linkerPublic: Uint8Array, sealed: Uint8Array): Promise<void> {
      if (!link) {
        throw new NoLinkError();
      }
      identity = await openLinkedIdentity(linkId, link.pair, link.secret, linkerPublic, sealed);
      link = null;
    },

    async prepareDevice(userId: number, oneTime = ONE_TIME_PACKAGES): Promise<PreparedDevice> {
      if (!identity) {
        throw new NoIdentityError();
      }
      const { secrets, identitySignature } = await createDevice(userId, identity.pkcs8);
      const packages = [
        ...await createKeyPackages(secrets, oneTime, false),
        ...await createKeyPackages(secrets, 1, true),
      ];
      pending = { userId, secrets, packages };
      return {
        signatureKey: secrets.signatureKey,
        credential: secrets.credential,
        identitySignature,
        keyPackages: packages.map((generated) => generated.upload),
      };
    },

    async commitDevice(deviceId: number, sessionId: number): Promise<LocalDevice> {
      if (!identity || !pending) {
        throw new NoIdentityError();
      }
      const storageKey = await newStorageKey();
      const secrets = pending.secrets;
      await store.clear();
      await store.put(DEVICE_SECRETS, {
        storageKey,
        sealed: await seal(storageKey, utf8(JSON.stringify({
          signKey: toBase64(secrets.signKey),
          signatureKey: toBase64(secrets.signatureKey),
          credential: toBase64(secrets.credential),
        })), SECRETS_LABEL),
        identityPublic: identity.publicKey,
        sealedIdentity: await seal(storageKey, identity.pkcs8, IDENTITY_LABEL),
      } satisfies StoredSecrets);
      await keepPackages(storageKey, pending.packages);
      const device: LocalDevice = {
        userId: pending.userId, deviceId, signatureKey: secrets.signatureKey, boundSessionId: sessionId,
      };
      await store.put(DEVICE_INFO, device);
      pending = null;
      return device;
    },

    async prepareKeyPackages(count: number): Promise<KeyPackageUpload[]> {
      const { stored, secrets } = await storedDevice();
      const packages = await createKeyPackages(secrets, count, false);
      await keepPackages(stored.storageKey, packages);
      return packages.map((generated) => generated.upload);
    },

    async forget(): Promise<void> {
      identity = null;
      pending = null;
      blind = null;
      unlockKeys = null;
      link = null;
      await store.clear();
    },
  };
}

export type DeviceVault = ReturnType<typeof createDeviceVault>;
