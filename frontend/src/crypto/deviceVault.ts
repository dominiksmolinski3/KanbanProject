import { fromBase64, toBase64, utf8 } from './bytes';
import {
  createDevice,
  createIdentity,
  createKeyPackages,
  newStorageKey,
  open,
  restoreIdentity,
  seal,
  type DeviceSecrets,
  type GeneratedKeyPackage,
  type KeyPackageUpload,
  type WrappedIdentity,
} from './keys';
import { ONE_TIME_PACKAGES } from './constants';
import { DEVICE_INFO, type KeyStore, type LocalDevice } from './keyStore';

const DEVICE_SECRETS = 'device-secrets';
const SECRETS_LABEL = utf8('device secrets');
const PACKAGE_LABEL = utf8('key package');

interface StoredSecrets {
  identity: CryptoKey;
  storageKey: CryptoKey;
  sealed: Uint8Array;
}

export interface PreparedDevice {
  signatureKey: Uint8Array;
  credential: Uint8Array;
  identitySignature: Uint8Array;
  keyPackages: KeyPackageUpload[];
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

export function createDeviceVault(store: KeyStore) {
  let identity: CryptoKey | null = null;
  let pending: { userId: number; secrets: DeviceSecrets; packages: GeneratedKeyPackage[] } | null = null;

  const sealSecrets = async (storageKey: CryptoKey, secrets: DeviceSecrets) => seal(storageKey, utf8(JSON.stringify({
    signKey: toBase64(secrets.signKey),
    signatureKey: toBase64(secrets.signatureKey),
    credential: toBase64(secrets.credential),
  })), SECRETS_LABEL);

  const keepPackages = async (storageKey: CryptoKey, packages: GeneratedKeyPackage[]) => {
    for (const { upload, secret } of packages) {
      const sealed = await seal(storageKey, utf8(JSON.stringify({
        initPrivateKey: toBase64(secret.initPrivateKey),
        hpkePrivateKey: toBase64(secret.hpkePrivateKey),
      })), PACKAGE_LABEL);
      await store.put(`key-package:${toBase64(upload.ref)}`, sealed);
    }
  };

  const storedDevice = async (): Promise<{ stored: StoredSecrets; secrets: DeviceSecrets }> => {
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
  };

  return {
    async createIdentity(recoveryKey: string): Promise<WrappedIdentity> {
      const created = await createIdentity(recoveryKey);
      identity = created.privateKey;
      return { publicKey: created.publicKey, wrappedPrivate: created.wrappedPrivate };
    },

    async restoreIdentity(recoveryKey: string, wrapped: WrappedIdentity): Promise<void> {
      identity = await restoreIdentity(recoveryKey, wrapped);
    },

    async prepareDevice(userId: number, oneTime = ONE_TIME_PACKAGES): Promise<PreparedDevice> {
      if (!identity) {
        throw new NoIdentityError();
      }
      const { secrets, identitySignature } = await createDevice(userId, identity);
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

    async commitDevice(deviceId: number): Promise<LocalDevice> {
      if (!identity || !pending) {
        throw new NoIdentityError();
      }
      const storageKey = await newStorageKey();
      await store.clear();
      await store.put(DEVICE_SECRETS, {
        identity,
        storageKey,
        sealed: await sealSecrets(storageKey, pending.secrets),
      } satisfies StoredSecrets);
      await keepPackages(storageKey, pending.packages);
      const device: LocalDevice = { userId: pending.userId, deviceId, signatureKey: pending.secrets.signatureKey };
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
      await store.clear();
    },
  };
}

export type DeviceVault = ReturnType<typeof createDeviceVault>;
