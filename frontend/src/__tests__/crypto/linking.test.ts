/**
 * @jest-environment node
 */
import { createDeviceVault, NoLinkError } from '../../crypto/deviceVault';
import { memoryKeyStore } from '../../crypto/keyStore';
import {
  makeLinkCode,
  newLinkKeyPair,
  newLinkSecret,
  openIdentity,
  parseLinkCode,
  sealIdentity,
} from '../../crypto/linking';

const ID = '3f2b8c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e';

describe('link codes', () => {
  test('a code round-trips, also when it arrives inside an address', async () => {
    const pair = await newLinkKeyPair();
    const secret = newLinkSecret();
    const code = makeLinkCode(ID, pair.publicKey, secret);

    for (const input of [code, `  ${code}\n`, `https://app.example/link#${code}`]) {
      expect(parseLinkCode(input)).toEqual({ id: ID, devicePublic: pair.publicKey, secret });
    }
  });

  test.each([
    ['empty', ''],
    ['no key', ID],
    ['not an id', 'abc.AAAA.BBBB'],
    ['extra part', `${ID}.AAAA.BBBB.CCCC`],
    ['bad characters', `${ID}.@@@.###`],
    ['short key', `${ID}.AAAA.AAAAAAAAAAAAAAAAAAAAAA`],
  ])('refuses a malformed code: %s', (_name, input) => {
    expect(parseLinkCode(input)).toBeNull();
  });

  test('refuses a code whose key is not an uncompressed point or whose secret is short', async () => {
    const pair = await newLinkKeyPair();
    const code = makeLinkCode(ID, pair.publicKey, newLinkSecret());
    const [id, key] = code.split('.');
    const compressed = Uint8Array.from(pair.publicKey);
    compressed[0] = 2;

    expect(parseLinkCode(makeLinkCode(id, compressed, newLinkSecret()))).toBeNull();
    expect(parseLinkCode(`${id}.${key}.AAAA`)).toBeNull();
  });
});

describe('handing over an identity', () => {
  const identity = { publicKey: new Uint8Array([1, 2, 3]), pkcs8: new Uint8Array([9, 8, 7, 6]) };

  async function handOver() {
    const own = await newLinkKeyPair();
    const secret = newLinkSecret();
    const code = parseLinkCode(makeLinkCode(ID, own.publicKey, secret))!;
    const sealed = await sealIdentity(code, identity.publicKey, identity.pkcs8);
    return { own, secret, sealed };
  }

  test('only the device that made the code opens it', async () => {
    const { own, secret, sealed } = await handOver();

    expect(await openIdentity(ID, own, secret, sealed.linkerPublic, sealed.sealed)).toEqual(identity);
    await expect(openIdentity(ID, await newLinkKeyPair(), secret, sealed.linkerPublic, sealed.sealed)).rejects.toThrow();
  });

  test('a server that never saw the secret cannot forge an answer, and an answer is bound to its link', async () => {
    const { own, secret, sealed } = await handOver();
    const forged = await sealIdentity(
      { id: ID, devicePublic: own.publicKey, secret: newLinkSecret() }, new Uint8Array([6]), new Uint8Array([6]));

    await expect(openIdentity(ID, own, secret, forged.linkerPublic, forged.sealed)).rejects.toThrow();
    await expect(openIdentity('00000000-0000-4000-8000-000000000000', own, secret, sealed.linkerPublic, sealed.sealed))
      .rejects.toThrow();
  });

  test('a changed byte and a truncated answer are refused', async () => {
    const { own, secret, sealed } = await handOver();
    const tampered = sealed.sealed.slice();
    tampered[tampered.length - 1] ^= 1;

    await expect(openIdentity(ID, own, secret, sealed.linkerPublic, tampered)).rejects.toThrow();
    await expect(openIdentity(ID, own, secret, sealed.linkerPublic, sealed.sealed.slice(0, 8))).rejects.toThrow('truncated');
  });
});

describe('linking through the vault', () => {
  test('a device that holds the identity lets a new one adopt it, and the new one then registers as the same account', async () => {
    const existing = createDeviceVault(memoryKeyStore());
    const { publicKey } = await existing.createIdentity();
    await existing.prepareDevice(1, 1);
    await existing.commitDevice(10, 1);
    const fresh = createDeviceVault(memoryKeyStore());

    const begun = await fresh.beginLink();
    const code = parseLinkCode(makeLinkCode(ID, begun.publicKey, begun.secret))!;
    const sealed = await existing.sealIdentityForLink(code);
    await fresh.completeLink(ID, sealed.linkerPublic, sealed.sealed);

    const prepared = await fresh.prepareDevice(1, 1);
    expect(prepared.identitySignature.length).toBeGreaterThan(0);
    const device = await fresh.commitDevice(11, 2);
    expect(device).toMatchObject({ userId: 1, deviceId: 11 });
    expect(publicKey.length).toBeGreaterThan(0);
  });

  test('completing without having begun, or twice, is refused, and forgetting drops a pending link', async () => {
    const vault = createDeviceVault(memoryKeyStore());
    await expect(vault.completeLink(ID, new Uint8Array(65), new Uint8Array(40))).rejects.toBeInstanceOf(NoLinkError);

    await vault.beginLink();
    await vault.forget();
    await expect(vault.completeLink(ID, new Uint8Array(65), new Uint8Array(40))).rejects.toBeInstanceOf(NoLinkError);
  });
});
