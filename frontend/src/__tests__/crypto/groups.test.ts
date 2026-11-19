/**
 * @jest-environment node
 */
import { toHex, utf8 } from '../../crypto/bytes';
import { ciphersuite, createKeyPackages, type DeviceSecrets } from '../../crypto/keys';
import {
  commitChanges,
  decodeKeyPackage,
  decodeState,
  encodeState,
  encrypt,
  foundGroup,
  groupEpoch,
  groupName,
  joinFromWelcome,
  receive,
  roster,
  type CredentialCheck,
} from '../../crypto/groups';

const text = (bytes: Uint8Array) => new TextDecoder().decode(bytes);

async function device(userId: number): Promise<DeviceSecrets> {
  const { signKey, publicKey } = await (await ciphersuite()).signature.keygen();
  return { signKey, signatureKey: publicKey, credential: utf8(`u${userId}.${toHex(crypto.getRandomValues(new Uint8Array(16)))}`) };
}

async function keyPackageOf(secrets: DeviceSecrets) {
  const [generated] = await createKeyPackages(secrets, 1, false);
  return {
    publicPackage: decodeKeyPackage(generated.upload.data),
    privatePackage: { ...generated.secret, signaturePrivateKey: secrets.signKey },
  };
}

const anyone: CredentialCheck = () => true;

describe('MLS groups', () => {
  test('a founder adds two devices, all three read each other, a rename travels in the group context, and a removed device reads nothing after', async () => {
    const alice = await device(1);
    const bob = await device(2);
    const carol = await device(3);
    const bobPackage = await keyPackageOf(bob);
    const carolPackage = await keyPackageOf(carol);

    let aliceState = await foundGroup(utf8('group-1'), alice, 'Launch', anyone);
    expect(groupEpoch(aliceState)).toBe(0);
    expect(groupName(aliceState)).toBe('Launch');

    const added = await commitChanges(aliceState, { add: [bobPackage.publicPackage, carolPackage.publicPackage] });
    aliceState = added.state;
    expect(groupEpoch(aliceState)).toBe(1);
    expect(added.welcome).not.toBeNull();

    let bobState = await joinFromWelcome(added.welcome!, bobPackage.publicPackage, bobPackage.privatePackage, anyone);
    let carolState = await joinFromWelcome(added.welcome!, carolPackage.publicPackage, carolPackage.privatePackage, anyone);
    expect(groupName(bobState)).toBe('Launch');
    expect(roster(bobState).map((leaf) => text(leaf.credential))).toEqual(
      [alice, bob, carol].map((each) => text(each.credential)));

    const hello = await encrypt(aliceState, utf8('hello'));
    aliceState = hello.state;
    const atBob = await receive(bobState, hello.body);
    expect(atBob.kind).toBe('application');
    bobState = atBob.state;
    expect(atBob.kind === 'application' && text(atBob.plaintext)).toBe('hello');

    const renamed = await commitChanges(bobState, { name: 'Relaunch' });
    bobState = renamed.state;
    aliceState = (await receive(aliceState, renamed.commit)).state;
    carolState = (await receive(carolState, renamed.commit)).state;
    expect(groupName(carolState)).toBe('Relaunch');

    const carolLeaf = roster(aliceState).find((leaf) => text(leaf.credential) === text(carol.credential))!;
    const removal = await commitChanges(aliceState, { remove: [carolLeaf.leafIndex] });
    aliceState = removal.state;
    bobState = (await receive(bobState, removal.commit)).state;
    expect(roster(bobState)).toHaveLength(2);

    const secret = await encrypt(aliceState, utf8('after carol left'));
    await expect(receive(carolState, secret.body)).rejects.toThrow();
    const atBobAgain = await receive(bobState, secret.body);
    expect(atBobAgain.kind === 'application' && text(atBobAgain.plaintext)).toBe('after carol left');
  });

  test('a stored state decodes into one that still decrypts', async () => {
    const alice = await device(1);
    const bob = await device(2);
    const bobPackage = await keyPackageOf(bob);
    const added = await commitChanges(await foundGroup(utf8('group-2'), alice, null, anyone),
      { add: [bobPackage.publicPackage] });
    const bobState = await joinFromWelcome(added.welcome!, bobPackage.publicPackage, bobPackage.privatePackage, anyone);

    const restored = decodeState(encodeState(bobState), anyone);
    const message = await encrypt(added.state, utf8('still here'));
    const received = await receive(restored, message.body);

    expect(received.kind === 'application' && text(received.plaintext)).toBe('still here');
    expect(groupName(restored)).toBeNull();
  });

  test('a join refuses a tree holding a credential the check rejects', async () => {
    const alice = await device(1);
    const bob = await device(2);
    const bobPackage = await keyPackageOf(bob);
    const added = await commitChanges(await foundGroup(utf8('group-3'), alice, null, anyone),
      { add: [bobPackage.publicPackage] });
    const refuseAlice: CredentialCheck = (credential) => text(credential) !== text(alice.credential);

    await expect(joinFromWelcome(added.welcome!, bobPackage.publicPackage, bobPackage.privatePackage, refuseAlice))
      .rejects.toThrow();
  });
});
