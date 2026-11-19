import { getJson, postJson, putJson } from './http';
import { toBase64 } from '../crypto/bytes';
import type { KeyPackageUpload, WrappedIdentity } from '../crypto/keys';
import type { PreparedDevice } from '../crypto/deviceVault';

const API_BASE_URL = '/api';

export interface MessagingDevice {
  id: number;
  sessionId: number | null;
  signatureKey: string;
  createdAt: string;
  keyPackages: number;
}

export interface MessagingStatus {
  enabled: boolean;
  identity: { publicKey: string; createdAt: string } | null;
  devices: MessagingDevice[];
}

export interface IdentityBackup {
  publicKey: string;
  wrappedPrivate: string;
}

const identityBody = (identity: WrappedIdentity) => ({
  publicKey: toBase64(identity.publicKey),
  wrappedPrivate: toBase64(identity.wrappedPrivate),
});

const packageBody = (packages: KeyPackageUpload[]) => packages.map((keyPackage) => ({
  ref: toBase64(keyPackage.ref),
  data: toBase64(keyPackage.data),
  lastResort: keyPackage.lastResort,
}));

export const cryptoApi = {
  status: () => getJson<MessagingStatus>(`${API_BASE_URL}/crypto/status`),

  createIdentity: (identity: WrappedIdentity) =>
    postJson<{ publicKey: string; createdAt: string }>(`${API_BASE_URL}/crypto/identity`, identityBody(identity)),

  replaceIdentity: (identity: WrappedIdentity) =>
    putJson<{ publicKey: string; createdAt: string }>(`${API_BASE_URL}/crypto/identity`, identityBody(identity)),

  identityBackup: () => getJson<IdentityBackup>(`${API_BASE_URL}/crypto/identity/backup`),

  registerDevice: (sessionId: number, device: PreparedDevice) =>
    postJson<MessagingDevice>(`${API_BASE_URL}/crypto/devices`, {
      sessionId,
      signatureKey: toBase64(device.signatureKey),
      credential: toBase64(device.credential),
      identitySignature: toBase64(device.identitySignature),
      keyPackages: packageBody(device.keyPackages),
    }),

  uploadKeyPackages: (deviceId: number, packages: KeyPackageUpload[]) =>
    postJson<{ available: number }>(`${API_BASE_URL}/crypto/devices/${deviceId}/key-packages`, {
      keyPackages: packageBody(packages),
    }),
};
