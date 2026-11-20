import { deleteChecked, getJson, postJson, postVoid, putJson } from './http';
import { toBase64 } from '../crypto/bytes';
import type { KeyPackageUpload } from '../crypto/keys';
import type { PinWrap, PreparedDevice, SignedWrap } from '../crypto/deviceVault';

const API_BASE_URL = '/api';

export interface MessagingDevice {
  id: number;
  sessionId: number | null;
  signatureKey: string;
  label: string | null;
  createdAt: string;
  lastSeenAt: string;
  keyPackages: number;
}

export interface MessagingIdentity {
  publicKey: string;
  createdAt: string;
  recoveryKey: boolean;
}

export interface PinStatus {
  available: boolean;
  set: boolean;
  attemptsLeft: number;
}

export interface MessagingStatus {
  enabled: boolean;
  identity: MessagingIdentity | null;
  pin: PinStatus | null;
  devices: MessagingDevice[];
}

export interface DeviceLink {
  id: string;
  devicePublic: string;
  linkerPublic: string | null;
  sealed: string | null;
  expiresAt: string;
}

export interface IdentityBackup {
  publicKey: string;
  wrappedPrivate: string;
}

const packageBody = (packages: KeyPackageUpload[]) => packages.map((keyPackage) => ({
  ref: toBase64(keyPackage.ref),
  data: toBase64(keyPackage.data),
  lastResort: keyPackage.lastResort,
}));

const identityBody = (publicKey: Uint8Array, recoveryWrap: Uint8Array | null) => ({
  publicKey: toBase64(publicKey),
  wrappedPrivate: recoveryWrap ? toBase64(recoveryWrap) : null,
});

export const cryptoApi = {
  status: () => getJson<MessagingStatus>(`${API_BASE_URL}/crypto/status`),

  createIdentity: (publicKey: Uint8Array, recoveryWrap: Uint8Array | null) =>
    postJson<MessagingIdentity>(`${API_BASE_URL}/crypto/identity`, identityBody(publicKey, recoveryWrap)),

  replaceIdentity: (publicKey: Uint8Array, recoveryWrap: Uint8Array | null) =>
    putJson<MessagingIdentity>(`${API_BASE_URL}/crypto/identity`, identityBody(publicKey, recoveryWrap)),

  identityBackup: () => getJson<IdentityBackup>(`${API_BASE_URL}/crypto/identity/backup`),

  setRecoveryKey: (wrap: SignedWrap) =>
    putJson<MessagingIdentity>(`${API_BASE_URL}/crypto/identity/backup`, {
      wrappedPrivate: toBase64(wrap.wrappedPrivate),
      signature: toBase64(wrap.signature),
    }),

  evaluatePin: (blinded: Uint8Array) =>
    postJson<{ evaluated: string }>(`${API_BASE_URL}/crypto/pin/evaluate`, { blinded: toBase64(blinded) }),

  setPin: (wrap: PinWrap) =>
    putJson<PinStatus>(`${API_BASE_URL}/crypto/pin`, {
      wrappedPrivate: toBase64(wrap.wrappedPrivate),
      verifier: toBase64(wrap.verifier),
      signature: toBase64(wrap.signature),
    }),

  unlockWithPin: (proof: Uint8Array) =>
    postJson<IdentityBackup>(`${API_BASE_URL}/crypto/pin/unlock`, { proof: toBase64(proof) }),

  registerDevice: (sessionId: number, device: PreparedDevice) =>
    postJson<MessagingDevice>(`${API_BASE_URL}/crypto/devices`, {
      sessionId,
      signatureKey: toBase64(device.signatureKey),
      credential: toBase64(device.credential),
      identitySignature: toBase64(device.identitySignature),
      keyPackages: packageBody(device.keyPackages),
    }),

  createLink: (devicePublic: Uint8Array) =>
    postJson<DeviceLink>(`${API_BASE_URL}/crypto/links`, { devicePublic: toBase64(devicePublic) }),

  getLink: (id: string) => getJson<DeviceLink>(`${API_BASE_URL}/crypto/links/${id}`),

  answerLink: (id: string, linkerPublic: Uint8Array, sealed: Uint8Array) =>
    postVoid(`${API_BASE_URL}/crypto/links/${id}/answer`,
      { linkerPublic: toBase64(linkerPublic), sealed: toBase64(sealed) }),

  cancelLink: (id: string) => deleteChecked(`${API_BASE_URL}/crypto/links/${id}`),

  attachSession: (deviceId: number, sessionId: number) =>
    postJson<MessagingDevice>(`${API_BASE_URL}/crypto/devices/${deviceId}/session`, { sessionId }),

  forgetDevice: (deviceId: number) => deleteChecked(`${API_BASE_URL}/crypto/devices/${deviceId}`),

  uploadKeyPackages: (deviceId: number, packages: KeyPackageUpload[]) =>
    postJson<{ available: number }>(`${API_BASE_URL}/crypto/devices/${deviceId}/key-packages`, {
      keyPackages: packageBody(packages),
    }),
};
