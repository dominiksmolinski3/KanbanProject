import { deviceVault } from '../../crypto/client';
import { fromBase64 } from '../../crypto/bytes';
import { makeLinkCode, parseLinkCode } from '../../crypto/linking';
import type { LocalDevice } from '../../crypto/keyStore';
import { cryptoApi, type IdentityBackup } from '../../services/cryptoApi';

const wrapped = (backup: IdentityBackup) => ({
  publicKey: fromBase64(backup.publicKey),
  wrappedPrivate: fromBase64(backup.wrappedPrivate),
});

export async function registerThisBrowser(userId: number, sessionId: number): Promise<LocalDevice> {
  const vault = deviceVault();
  const prepared = await vault.prepareDevice(userId);
  const registered = await cryptoApi.registerDevice(sessionId, prepared);
  return vault.commitDevice(registered.id, sessionId);
}

export async function savePin(userId: number, pin: string): Promise<void> {
  const vault = deviceVault();
  const { evaluated } = await cryptoApi.evaluatePin(await vault.blindPin(userId, pin));
  await cryptoApi.setPin(await vault.wrapForPin(fromBase64(evaluated)));
}

export interface SetUp {
  userId: number;
  sessionId: number;
  pin: string | null;
  recoveryKey: string | null;
  replace: boolean;
}

export async function setUpMessaging({ userId, sessionId, pin, recoveryKey, replace }: SetUp): Promise<LocalDevice> {
  const vault = deviceVault();
  const { publicKey } = await vault.createIdentity();
  const recoveryWrap = recoveryKey ? (await vault.wrapForRecovery(recoveryKey)).wrappedPrivate : null;
  if (replace) {
    await cryptoApi.replaceIdentity(publicKey, recoveryWrap);
  } else {
    await cryptoApi.createIdentity(publicKey, recoveryWrap);
  }
  if (pin) {
    await savePin(userId, pin);
  }
  return registerThisBrowser(userId, sessionId);
}

export async function unlockWithPin(userId: number, pin: string): Promise<void> {
  const vault = deviceVault();
  const { evaluated } = await cryptoApi.evaluatePin(await vault.blindPin(userId, pin));
  const backup = await cryptoApi.unlockWithPin(await vault.pinProof(fromBase64(evaluated)));
  await vault.restoreWithPin(wrapped(backup));
}

export async function unlockWithRecoveryKey(recoveryKey: string): Promise<void> {
  await deviceVault().restoreWithRecoveryKey(recoveryKey, wrapped(await cryptoApi.identityBackup()));
}

export interface StartedLink {
  id: string;
  code: string;
  expiresAt: string;
}

export async function startLink(): Promise<StartedLink> {
  const vault = deviceVault();
  const { publicKey, secret } = await vault.beginLink();
  const created = await cryptoApi.createLink(publicKey);
  return { id: created.id, code: makeLinkCode(created.id, publicKey, secret), expiresAt: created.expiresAt };
}

export async function receiveLink(id: string): Promise<boolean> {
  const link = await cryptoApi.getLink(id);
  if (!link.sealed || !link.linkerPublic) return false;
  await deviceVault().completeLink(id, fromBase64(link.linkerPublic), fromBase64(link.sealed));
  await cryptoApi.cancelLink(id).catch(() => undefined);
  return true;
}

export class BadLinkCodeError extends Error {
  constructor() {
    super('BadLinkCode');
    this.name = 'BadLinkCodeError';
  }
}

export async function approveLink(input: string): Promise<void> {
  const code = parseLinkCode(input);
  if (!code) throw new BadLinkCodeError();
  const { linkerPublic, sealed } = await deviceVault().sealIdentityForLink(code);
  await cryptoApi.answerLink(code.id, linkerPublic, sealed);
}

export async function addRecoveryKey(recoveryKey: string): Promise<void> {
  await cryptoApi.setRecoveryKey(await deviceVault().wrapForRecovery(recoveryKey));
}
