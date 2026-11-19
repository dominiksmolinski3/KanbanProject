import {
  createApplicationMessage,
  createCommit,
  createGroup,
  decodeGroupState,
  decodeMlsMessage,
  defaultKeyPackageEqualityConfig,
  defaultKeyRetentionConfig,
  defaultLifetimeConfig,
  defaultPaddingConfig,
  emptyPskIndex,
  encodeGroupState,
  encodeMlsMessage,
  generateKeyPackageWithKey,
  joinGroup,
  processMessage,
  zeroOutUint8Array,
  type ClientConfig,
  type ClientState,
  type Credential,
  type Extension,
  type KeyPackage,
  type PrivateKeyPackage,
  type Proposal,
} from 'ts-mls';
import { ciphersuite, GROUP_NAME_EXTENSION, messagingCapabilities } from './keys';

export { GROUP_NAME_EXTENSION };

const OWN_PACKAGE_LIFETIME_SECONDS = 24 * 60 * 60;

export interface GroupSigner {
  signKey: Uint8Array;
  signatureKey: Uint8Array;
  credential: Uint8Array;
}

export interface RosterLeaf {
  leafIndex: number;
  credential: Uint8Array;
  signatureKey: Uint8Array;
}

export type CredentialCheck = (credential: Uint8Array, signatureKey: Uint8Array) => boolean;

export interface PreparedCommit {
  state: ClientState;
  commit: Uint8Array;
  welcome: Uint8Array | null;
}

export class MlsFormatError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'MlsFormatError';
  }
}

export const clientConfig = (check: CredentialCheck): ClientConfig => ({
  keyRetentionConfig: defaultKeyRetentionConfig,
  lifetimeConfig: defaultLifetimeConfig,
  keyPackageEqualityConfig: defaultKeyPackageEqualityConfig,
  paddingConfig: defaultPaddingConfig,
  authService: {
    validateCredential: async (credential: Credential, signatureKey: Uint8Array) =>
      credential.credentialType === 'basic' && check(credential.identity, signatureKey),
  },
});

const nameExtension = (name: string): Extension => ({
  extensionType: GROUP_NAME_EXTENSION,
  extensionData: new TextEncoder().encode(name),
});

const isNameExtension = (extension: Extension) => extension.extensionType === GROUP_NAME_EXTENSION;

const consume = (keys: Uint8Array[]) => keys.forEach(zeroOutUint8Array);

export function groupName(state: ClientState): string | null {
  const extension = state.groupContext.extensions.find(isNameExtension);
  return extension ? new TextDecoder().decode(extension.extensionData) : null;
}

export const groupEpoch = (state: ClientState): number => Number(state.groupContext.epoch);

export function roster(state: ClientState): RosterLeaf[] {
  const leaves: RosterLeaf[] = [];
  state.ratchetTree.forEach((node, index) => {
    if (index % 2 === 0 && node?.nodeType === 'leaf' && node.leaf.credential.credentialType === 'basic') {
      leaves.push({
        leafIndex: index / 2,
        credential: node.leaf.credential.identity,
        signatureKey: node.leaf.signaturePublicKey,
      });
    }
  });
  return leaves;
}

export function decodeKeyPackage(data: Uint8Array): KeyPackage {
  const decoded = decodeMlsMessage(data, 0)?.[0];
  if (decoded?.wireformat !== 'mls_key_package') {
    throw new MlsFormatError('Not a key package');
  }
  return decoded.keyPackage;
}

async function ownKeyPackage(signer: GroupSigner): Promise<{ publicPackage: KeyPackage; privatePackage: PrivateKeyPackage }> {
  const seconds = Math.floor(Date.now() / 1000);
  return generateKeyPackageWithKey(
    { credentialType: 'basic', identity: signer.credential },
    messagingCapabilities(),
    { notBefore: BigInt(seconds - 3600), notAfter: BigInt(seconds + OWN_PACKAGE_LIFETIME_SECONDS) },
    [],
    { signKey: signer.signKey, publicKey: signer.signatureKey },
    await ciphersuite(),
  );
}

export async function foundGroup(groupId: Uint8Array, signer: GroupSigner, name: string | null,
  check: CredentialCheck): Promise<ClientState> {
  const own = await ownKeyPackage(signer);
  return createGroup(groupId, own.publicPackage, own.privatePackage, name ? [nameExtension(name)] : [],
    await ciphersuite(), clientConfig(check));
}

export interface Changes {
  add?: KeyPackage[];
  remove?: number[];
  name?: string;
}

export async function commitChanges(state: ClientState, changes: Changes): Promise<PreparedCommit> {
  const proposals: Proposal[] = [
    ...(changes.remove ?? []).map((removed): Proposal => ({ proposalType: 'remove', remove: { removed } })),
    ...(changes.add ?? []).map((keyPackage): Proposal => ({ proposalType: 'add', add: { keyPackage } })),
  ];
  if (changes.name !== undefined) {
    proposals.push({
      proposalType: 'group_context_extensions',
      groupContextExtensions: {
        extensions: [...state.groupContext.extensions.filter((extension) => !isNameExtension(extension)),
          nameExtension(changes.name)],
      },
    });
  }
  const result = await createCommit({ state, cipherSuite: await ciphersuite() },
    { extraProposals: proposals, ratchetTreeExtension: true });
  consume(result.consumed);
  return {
    state: result.newState,
    commit: encodeMlsMessage(result.commit),
    welcome: result.welcome
      ? encodeMlsMessage({ welcome: result.welcome, wireformat: 'mls_welcome', version: 'mls10' })
      : null,
  };
}

export async function joinFromWelcome(welcome: Uint8Array, keyPackage: KeyPackage, privatePackage: PrivateKeyPackage,
  check: CredentialCheck): Promise<ClientState> {
  const decoded = decodeMlsMessage(welcome, 0)?.[0];
  if (decoded?.wireformat !== 'mls_welcome') {
    throw new MlsFormatError('Not a welcome');
  }
  return joinGroup(decoded.welcome, keyPackage, privatePackage, emptyPskIndex, await ciphersuite(), undefined,
    undefined, clientConfig(check));
}

export type Received =
  | { kind: 'commit'; state: ClientState }
  | { kind: 'application'; state: ClientState; plaintext: Uint8Array };

export async function receive(state: ClientState, body: Uint8Array): Promise<Received> {
  const decoded = decodeMlsMessage(body, 0)?.[0];
  if (decoded?.wireformat !== 'mls_private_message' && decoded?.wireformat !== 'mls_public_message') {
    throw new MlsFormatError('Not a group message');
  }
  const result = await processMessage(decoded, state, emptyPskIndex, () => 'accept', await ciphersuite());
  consume(result.consumed);
  if (result.kind === 'applicationMessage') {
    return { kind: 'application', state: result.newState, plaintext: result.message };
  }
  return { kind: 'commit', state: result.newState };
}

export async function encrypt(state: ClientState, plaintext: Uint8Array): Promise<{ state: ClientState; body: Uint8Array }> {
  const result = await createApplicationMessage(state, plaintext, await ciphersuite());
  consume(result.consumed);
  return {
    state: result.newState,
    body: encodeMlsMessage({ privateMessage: result.privateMessage, wireformat: 'mls_private_message', version: 'mls10' }),
  };
}

export const encodeState = (state: ClientState): Uint8Array => encodeGroupState(state);

export function decodeState(bytes: Uint8Array, check: CredentialCheck): ClientState {
  const decoded = decodeGroupState(bytes, 0)?.[0];
  if (!decoded) {
    throw new MlsFormatError('Unreadable group state');
  }
  return { ...decoded, clientConfig: clientConfig(check) };
}
