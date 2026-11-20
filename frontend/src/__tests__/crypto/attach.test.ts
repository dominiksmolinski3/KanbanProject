import { attachThisBrowser } from '../../crypto/attach';
import { ApiError } from '../../services/apiError';

jest.mock('../../services/cryptoApi', () => ({ cryptoApi: { attachSession: jest.fn() } }));
jest.mock('../../crypto/keyStore', () => ({
  readLocalDevice: jest.fn(),
  rebindLocalDevice: jest.fn(),
  wipeLocalKeys: jest.fn(),
}));

const { cryptoApi } = jest.requireMock('../../services/cryptoApi');
const { readLocalDevice, rebindLocalDevice, wipeLocalKeys } = jest.requireMock('../../crypto/keyStore');

const device = { userId: 5, deviceId: 3, signatureKey: new Uint8Array(32), boundSessionId: 12 };

describe('attaching this browser to its sign-in', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    readLocalDevice.mockResolvedValue(device);
    cryptoApi.attachSession.mockResolvedValue({});
  });

  test('does nothing on a browser that holds no key', async () => {
    readLocalDevice.mockResolvedValue(null);

    await attachThisBrowser(20, 5);

    expect(cryptoApi.attachSession).not.toHaveBeenCalled();
    expect(wipeLocalKeys).not.toHaveBeenCalled();
  });

  test('moves the device onto a new sign-in and remembers it', async () => {
    await attachThisBrowser(20, 5);

    expect(cryptoApi.attachSession).toHaveBeenCalledWith(3, 20);
    expect(rebindLocalDevice).toHaveBeenCalledWith(20);
  });

  test('stays quiet when the device is already on this sign-in, or there is none', async () => {
    await attachThisBrowser(12, 5);
    await attachThisBrowser(null, 5);

    expect(cryptoApi.attachSession).not.toHaveBeenCalled();
  });

  test('deletes the keys another account left on this browser', async () => {
    await attachThisBrowser(20, 6);

    expect(wipeLocalKeys).toHaveBeenCalled();
    expect(cryptoApi.attachSession).not.toHaveBeenCalled();
  });

  test('deletes the keys when the server says the device was removed', async () => {
    cryptoApi.attachSession.mockRejectedValue(new ApiError('gone', { status: 404, code: 'CRYPTO_DEVICE_NOT_FOUND' }));

    await attachThisBrowser(20);

    expect(wipeLocalKeys).toHaveBeenCalled();
    expect(rebindLocalDevice).not.toHaveBeenCalled();
  });

  test('keeps the keys through any other failure, such as messaging being switched off', async () => {
    const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
    cryptoApi.attachSession.mockRejectedValue(new ApiError('off', { status: 404, code: 'MESSAGING_UNAVAILABLE' }));

    await attachThisBrowser(20);

    expect(wipeLocalKeys).not.toHaveBeenCalled();
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});
