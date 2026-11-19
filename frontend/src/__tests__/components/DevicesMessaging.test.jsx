import React from 'react';
import { render, screen, fireEvent, waitFor, within } from '../../test-utils/rtl';
import '@testing-library/jest-dom';
import { MemoryRouter } from 'react-router-dom';
import Devices from '../../components/Devices';

const translation = { t: (key, options) => (options?.count !== undefined ? `${key}:${options.count}` : key), i18n: { language: 'en' } };
jest.mock('react-i18next', () => ({
  useTranslation: () => translation
}));

jest.mock('../../context/AuthContext', () => ({
  useAuth: () => ({ user: { id: 5 }, logout: jest.fn() })
}));

jest.mock('../../services/authService', () => ({
  authService: { listDevices: jest.fn(), endDevice: jest.fn() }
}));

let mockSessionId = '12';
jest.mock('../../services/session', () => ({
  ...jest.requireActual('../../services/session'),
  getSessionId: () => mockSessionId
}));

jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn() }
}));

jest.mock('../../services/cryptoApi', () => ({
  cryptoApi: {
    status: jest.fn(),
    createIdentity: jest.fn(),
    replaceIdentity: jest.fn(),
    identityBackup: jest.fn(),
    setRecoveryKey: jest.fn(),
    evaluatePin: jest.fn(),
    setPin: jest.fn(),
    unlockWithPin: jest.fn(),
    registerDevice: jest.fn(),
    attachSession: jest.fn(),
    forgetDevice: jest.fn(),
    uploadKeyPackages: jest.fn()
  }
}));

const mockVault = {
  createIdentity: jest.fn(),
  wrapForRecovery: jest.fn(),
  restoreWithRecoveryKey: jest.fn(),
  blindPin: jest.fn(),
  wrapForPin: jest.fn(),
  pinProof: jest.fn(),
  restoreWithPin: jest.fn(),
  prepareDevice: jest.fn(),
  commitDevice: jest.fn(),
  prepareKeyPackages: jest.fn(),
  forget: jest.fn()
};
jest.mock('../../crypto/client', () => ({
  ...jest.requireActual('../../crypto/client'),
  deviceVault: () => mockVault
}));

jest.mock('../../crypto/keyStore', () => ({
  ...jest.requireActual('../../crypto/keyStore'),
  readLocalDevice: jest.fn(),
  wipeLocalKeys: jest.fn()
}));

jest.mock('../../crypto/attach', () => ({ attachThisBrowser: jest.fn(() => Promise.resolve()) }));

const { authService } = require('../../services/authService');
const { cryptoApi } = require('../../services/cryptoApi');
const { readLocalDevice, wipeLocalKeys } = require('../../crypto/keyStore');
const { attachThisBrowser } = require('../../crypto/attach');
const { VaultError } = require('../../crypto/client');
const { ApiError } = require('../../services/apiError');
const { toast } = require('react-toastify');

const KEY = btoa(String.fromCharCode(...Array.from({ length: 32 }, (_, index) => index)));
const FINGERPRINT = '0001 0203 0405 0607 0809 0a0b 0c0d 0e0f';
const RECOVERY_KEY = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789ABCDEFGHJKLMNPQR';
const EVALUATED = btoa('evaluated');

const session = (overrides = {}) => ({
  id: 12,
  ipAddress: '203.0.113.7',
  userAgent: 'Mozilla/5.0 (Macintosh)',
  signedInAt: '2026-08-01T09:00:00Z',
  lastSeenAt: '2026-09-02T11:30:00Z',
  expiresAt: '2026-10-02T11:30:00Z',
  ...overrides
});

const device = (overrides = {}) => ({
  id: 3, sessionId: 12, signatureKey: KEY, label: 'Firefox', createdAt: '2026-11-18T10:00:00Z',
  lastSeenAt: '2026-11-18T10:00:00Z', keyPackages: 100, ...overrides
});

const identity = (overrides = {}) => ({ publicKey: KEY, createdAt: '2026-11-18T09:00:00Z', recoveryKey: true, ...overrides });
const pin = (overrides = {}) => ({ available: true, set: true, attemptsLeft: 10, ...overrides });
const status = (overrides = {}) => ({ enabled: true, identity: null, pin: pin({ set: false }), devices: [], ...overrides });
const prepared = { signatureKey: new Uint8Array(32), credential: new Uint8Array(4), identitySignature: new Uint8Array(64), keyPackages: [] };
const local = { userId: 5, deviceId: 3, signatureKey: new Uint8Array(32), boundSessionId: 12 };

const renderPage = () => render(<MemoryRouter><Devices /></MemoryRouter>);
const panel = () => screen.getByRole('region', { name: 'devices.messaging.title' });
const dialog = () => screen.getByRole('dialog');
const click = (container, name) => fireEvent.click(within(container).getByRole('button', { name }));
const type = (container, label, value) => fireEvent.change(within(container).getByLabelText(label), { target: { value } });

const confirmSaved = (container) => {
  const groups = within(container).getByRole('group', { name: 'devices.messaging.setup.keyLabel' });
  const last = Array.from(groups.children).at(-1).textContent;
  fireEvent.click(within(container).getByRole('checkbox'));
  type(container, 'devices.messaging.setup.checkLabel', last.toLowerCase());
  return groups;
};

const choosePin = (value = '135790', repeat = value) => {
  type(dialog(), 'devices.messaging.pin.label', value);
  type(dialog(), 'devices.messaging.pin.repeat', repeat);
};

describe('Devices: encrypted messaging keys', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockSessionId = '12';
    window.confirm = jest.fn(() => true);
    authService.listDevices.mockResolvedValue([session(), session({ id: 13, userAgent: 'Firefox/1' })]);
    authService.endDevice.mockResolvedValue(undefined);
    cryptoApi.status.mockResolvedValue(status());
    readLocalDevice.mockResolvedValue(null);
    mockVault.createIdentity.mockResolvedValue({ publicKey: new Uint8Array(32) });
    mockVault.wrapForRecovery.mockResolvedValue({ wrappedPrivate: new Uint8Array([7]), signature: new Uint8Array(64) });
    mockVault.blindPin.mockResolvedValue(new Uint8Array(65));
    mockVault.wrapForPin.mockResolvedValue({ wrappedPrivate: new Uint8Array(1), verifier: new Uint8Array(32), signature: new Uint8Array(64) });
    mockVault.pinProof.mockResolvedValue(new Uint8Array(32));
    mockVault.prepareDevice.mockResolvedValue(prepared);
    mockVault.commitDevice.mockResolvedValue(local);
    cryptoApi.createIdentity.mockResolvedValue(identity());
    cryptoApi.replaceIdentity.mockResolvedValue(identity());
    cryptoApi.evaluatePin.mockResolvedValue({ evaluated: EVALUATED });
    cryptoApi.setPin.mockResolvedValue(pin());
    cryptoApi.unlockWithPin.mockResolvedValue({ publicKey: KEY, wrappedPrivate: btoa('pin-wrapped') });
    cryptoApi.identityBackup.mockResolvedValue({ publicKey: KEY, wrappedPrivate: btoa('wrapped') });
    cryptoApi.registerDevice.mockResolvedValue(device());
    cryptoApi.forgetDevice.mockResolvedValue(true);
  });

  test('shows nothing about messaging while the feature is off', async () => {
    cryptoApi.status.mockResolvedValue({ enabled: false, identity: null, pin: null, devices: [] });

    renderPage();

    await waitFor(() => expect(screen.getByText('Firefox/1')).toBeInTheDocument());
    await waitFor(() => expect(cryptoApi.status).toHaveBeenCalled());
    expect(screen.queryByRole('region', { name: 'devices.messaging.title' })).not.toBeInTheDocument();
    expect(screen.queryByText('devices.fields.messagingKey')).not.toBeInTheDocument();
    expect(readLocalDevice).not.toHaveBeenCalled();
  });

  test('shows nothing about messaging when its status cannot be read', async () => {
    cryptoApi.status.mockRejectedValue(new Error('down'));

    renderPage();

    await waitFor(() => expect(cryptoApi.status).toHaveBeenCalled());
    expect(screen.queryByRole('region', { name: 'devices.messaging.title' })).not.toBeInTheDocument();
  });

  describe('setting up a new account', () => {
    const openSetup = async () => {
      renderPage();
      await waitFor(() => expect(within(panel()).getByText('devices.messaging.notSetUp')).toBeInTheDocument());
      click(panel(), 'devices.messaging.buttons.setUp');
      click(dialog(), 'devices.messaging.buttons.setUp');
    };

    test('with a PIN alone: identity, then PIN, then this browser', async () => {
      await openSetup();
      expect(within(dialog()).getByText('devices.messaging.pin.limits')).toBeInTheDocument();
      choosePin();
      cryptoApi.status.mockResolvedValue(status({ identity: identity({ recoveryKey: false }), pin: pin(), devices: [device()] }));
      readLocalDevice.mockResolvedValue(local);

      click(dialog(), 'devices.messaging.setup.finish');

      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('devices.messaging.setup.done'));
      expect(cryptoApi.createIdentity).toHaveBeenCalledWith(expect.any(Uint8Array), null);
      expect(mockVault.wrapForRecovery).not.toHaveBeenCalled();
      expect(mockVault.blindPin).toHaveBeenCalledWith(5, '135790');
      expect(mockVault.wrapForPin).toHaveBeenCalledWith(expect.any(Uint8Array));
      expect(cryptoApi.setPin).toHaveBeenCalled();
      expect(cryptoApi.registerDevice).toHaveBeenCalledWith(12, prepared);
      expect(mockVault.commitDevice).toHaveBeenCalledWith(3, 12);
      expect(cryptoApi.createIdentity.mock.invocationCallOrder[0]).toBeLessThan(cryptoApi.setPin.mock.invocationCallOrder[0]);
      expect(cryptoApi.setPin.mock.invocationCallOrder[0]).toBeLessThan(cryptoApi.registerDevice.mock.invocationCallOrder[0]);
      await waitFor(() => expect(within(panel()).getByText('devices.messaging.ready')).toBeInTheDocument());
      expect(within(panel()).getByText('devices.messaging.noRecoveryKey')).toBeInTheDocument();
    });

    test('refuses a PIN that is not six digits, or that does not match its repeat', async () => {
      await openSetup();

      choosePin('1234');
      click(dialog(), 'devices.messaging.setup.finish');
      expect(within(dialog()).getByRole('alert')).toHaveTextContent('devices.messaging.pin.invalid');

      choosePin('135790', '135791');
      click(dialog(), 'devices.messaging.setup.finish');
      expect(within(dialog()).getByRole('alert')).toHaveTextContent('devices.messaging.pin.mismatch');
      expect(mockVault.createIdentity).not.toHaveBeenCalled();
    });

    test('with a recovery key as well: the key must be saved and checked before anything is created', async () => {
      await openSetup();
      choosePin();
      fireEvent.click(within(dialog()).getByRole('checkbox'));
      click(dialog(), 'devices.messaging.buttons.continue');

      const finish = within(dialog()).getByRole('button', { name: 'devices.messaging.setup.finish' });
      expect(finish).toBeDisabled();
      const groups = confirmSaved(dialog());
      const shown = Array.from(groups.children).map((group) => group.textContent).join('');
      fireEvent.click(finish);

      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('devices.messaging.setup.done'));
      expect(mockVault.wrapForRecovery).toHaveBeenCalledWith(shown);
      expect(cryptoApi.createIdentity).toHaveBeenCalledWith(expect.any(Uint8Array), new Uint8Array([7]));
      expect(cryptoApi.setPin).toHaveBeenCalled();
    });

    test('without a PIN on this server, the recovery key is required and no PIN is sent', async () => {
      cryptoApi.status.mockResolvedValue(status({ pin: pin({ available: false, set: false }) }));
      await openSetup();

      expect(within(dialog()).getByText('devices.messaging.setup.cannotSkip')).toBeInTheDocument();
      confirmSaved(dialog());
      click(dialog(), 'devices.messaging.setup.finish');

      await waitFor(() => expect(toast.success).toHaveBeenCalled());
      expect(mockVault.blindPin).not.toHaveBeenCalled();
      expect(cryptoApi.setPin).not.toHaveBeenCalled();
      expect(cryptoApi.createIdentity).toHaveBeenCalledWith(expect.any(Uint8Array), new Uint8Array([7]));
    });

    test('keeps the dialog open and says why when the server refuses', async () => {
      cryptoApi.createIdentity.mockRejectedValue(new Error('errors.codes.IDENTITY_KEY_EXISTS'));
      await openSetup();
      choosePin();

      click(dialog(), 'devices.messaging.setup.finish');

      expect(await within(dialog()).findByRole('alert')).toHaveTextContent('errors.codes.IDENTITY_KEY_EXISTS');
      expect(mockVault.prepareDevice).not.toHaveBeenCalled();
    });

    test('refuses to register a browser whose session it cannot name', async () => {
      mockSessionId = null;
      await openSetup();
      choosePin();

      click(dialog(), 'devices.messaging.setup.finish');

      expect(await within(dialog()).findByRole('alert')).toHaveTextContent('devices.messaging.errors.noSession');
      expect(cryptoApi.createIdentity).not.toHaveBeenCalled();
    });

    test('copies and downloads the recovery key, and says so when copying fails', async () => {
      const writeText = jest.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('denied'));
      Object.assign(navigator, { clipboard: { writeText } });
      URL.createObjectURL = jest.fn(() => 'blob:key');
      URL.revokeObjectURL = jest.fn();
      const anchorClick = jest.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
      await openSetup();
      choosePin();
      fireEvent.click(within(dialog()).getByRole('checkbox'));
      click(dialog(), 'devices.messaging.buttons.continue');

      click(dialog(), 'devices.messaging.setup.copy');
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('devices.messaging.setup.copied'));
      expect(writeText.mock.calls[0][0]).toMatch(/^([A-Z2-9]{4} ){11}[A-Z2-9]{4}$/);
      click(dialog(), 'devices.messaging.setup.copy');
      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('devices.messaging.setup.copyFailed'));

      click(dialog(), 'devices.messaging.setup.download');
      expect(anchorClick).toHaveBeenCalled();
      expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:key');
      anchorClick.mockRestore();
    });

    test('a cancelled setup creates nothing', async () => {
      renderPage();
      await waitFor(() => expect(within(panel()).getByText('devices.messaging.notSetUp')).toBeInTheDocument());
      click(panel(), 'devices.messaging.buttons.setUp');

      click(dialog(), 'devices.messaging.buttons.cancel');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(mockVault.createIdentity).not.toHaveBeenCalled();
    });
  });

  describe('an account set up elsewhere', () => {
    beforeEach(() => {
      cryptoApi.status.mockResolvedValue(status({ identity: identity(), pin: pin(), devices: [device({ id: 8, sessionId: 13 })] }));
    });

    const openUnlock = async (button) => {
      renderPage();
      await waitFor(() => expect(within(panel()).getByText('devices.messaging.elsewhere')).toBeInTheDocument());
      click(panel(), button);
      return dialog();
    };

    test('shows which other session holds a key', async () => {
      renderPage();
      await waitFor(() => expect(within(panel()).getByText('devices.messaging.elsewhere')).toBeInTheDocument());

      expect(screen.getByText(FINGERPRINT)).toBeInTheDocument();
      expect(screen.getAllByText('devices.fields.noMessagingKey')).toHaveLength(1);
    });

    test('unlocks this browser with the PIN and registers it', async () => {
      const unlock = await openUnlock('devices.messaging.buttons.enterPin');

      type(unlock, 'devices.messaging.pin.label', '135790');
      click(unlock, 'devices.messaging.unlock.submit');

      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('devices.messaging.setup.done'));
      expect(mockVault.blindPin).toHaveBeenCalledWith(5, '135790');
      expect(cryptoApi.unlockWithPin).toHaveBeenCalledWith(expect.any(Uint8Array));
      expect(mockVault.restoreWithPin).toHaveBeenCalledWith({ publicKey: expect.any(Uint8Array), wrappedPrivate: expect.any(Uint8Array) });
      expect(cryptoApi.registerDevice).toHaveBeenCalledWith(12, prepared);
      expect(cryptoApi.createIdentity).not.toHaveBeenCalled();
    });

    test('a wrong PIN says so and how many tries are left', async () => {
      cryptoApi.unlockWithPin.mockRejectedValue(new ApiError('wrong', { status: 400, code: 'PIN_INCORRECT' }));
      const unlock = await openUnlock('devices.messaging.buttons.enterPin');
      cryptoApi.status.mockResolvedValue(status({ identity: identity(), pin: pin({ attemptsLeft: 9 }), devices: [] }));

      type(unlock, 'devices.messaging.pin.label', '000000');
      click(unlock, 'devices.messaging.unlock.submit');

      expect(await within(unlock).findByRole('alert')).toHaveTextContent('devices.messaging.errors.wrongPin');
      await waitFor(() => expect(within(unlock).getByText('devices.messaging.unlock.attemptsLeft:9')).toBeInTheDocument());
      expect(cryptoApi.registerDevice).not.toHaveBeenCalled();
    });

    test('a PIN locked by the server says to use the recovery key', async () => {
      cryptoApi.evaluatePin.mockRejectedValue(new ApiError('locked', { status: 423, code: 'PIN_LOCKED' }));
      const unlock = await openUnlock('devices.messaging.buttons.enterPin');

      type(unlock, 'devices.messaging.pin.label', '000000');
      click(unlock, 'devices.messaging.unlock.submit');

      expect(await within(unlock).findByRole('alert')).toHaveTextContent('devices.messaging.errors.pinLocked');
    });

    test('a PIN that is not six digits never reaches the server', async () => {
      const unlock = await openUnlock('devices.messaging.buttons.enterPin');

      type(unlock, 'devices.messaging.pin.label', '12');
      click(unlock, 'devices.messaging.unlock.submit');

      expect(within(unlock).getByRole('alert')).toHaveTextContent('devices.messaging.pin.invalid');
      expect(mockVault.blindPin).not.toHaveBeenCalled();
    });

    test('a locked PIN is not offered, and an account without a recovery key does not offer one', async () => {
      cryptoApi.status.mockResolvedValue(status({ identity: identity({ recoveryKey: false }), pin: pin({ attemptsLeft: 0 }), devices: [] }));
      renderPage();

      await waitFor(() => expect(within(panel()).getByText('devices.messaging.errors.pinLocked')).toBeInTheDocument());
      expect(within(panel()).queryByRole('button', { name: 'devices.messaging.buttons.enterPin' })).not.toBeInTheDocument();
      expect(within(panel()).queryByRole('button', { name: 'devices.messaging.buttons.useRecoveryKey' })).not.toBeInTheDocument();
      expect(within(panel()).getByRole('button', { name: 'devices.messaging.buttons.startOver' })).toBeInTheDocument();
    });

    test('adds this browser with the recovery key, however it was typed', async () => {
      const unlock = await openUnlock('devices.messaging.buttons.useRecoveryKey');

      type(unlock, 'devices.messaging.recover.label', RECOVERY_KEY.toLowerCase().match(/.{4}/g).join('-'));
      click(unlock, 'devices.messaging.recover.submit');

      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('devices.messaging.setup.done'));
      expect(mockVault.restoreWithRecoveryKey).toHaveBeenCalledWith(RECOVERY_KEY, {
        publicKey: expect.any(Uint8Array), wrappedPrivate: expect.any(Uint8Array)
      });
      expect(cryptoApi.registerDevice).toHaveBeenCalled();
    });

    test('refuses a recovery key that cannot be one without asking the server', async () => {
      const unlock = await openUnlock('devices.messaging.buttons.useRecoveryKey');

      type(unlock, 'devices.messaging.recover.label', 'ABCD');
      click(unlock, 'devices.messaging.recover.submit');

      expect(within(unlock).getByRole('alert')).toHaveTextContent('devices.messaging.errors.malformedKey');
      expect(cryptoApi.identityBackup).not.toHaveBeenCalled();
    });

    test('says plainly when the recovery key belongs to another account, and passes other failures through', async () => {
      mockVault.restoreWithRecoveryKey.mockRejectedValueOnce(new VaultError('RecoveryKeyMismatchError', 'RecoveryKeyMismatch'));
      mockVault.restoreWithRecoveryKey.mockRejectedValueOnce(new VaultError('OperationError', 'broken'));
      const unlock = await openUnlock('devices.messaging.buttons.useRecoveryKey');
      type(unlock, 'devices.messaging.recover.label', RECOVERY_KEY);

      click(unlock, 'devices.messaging.recover.submit');
      expect(await within(unlock).findByRole('alert')).toHaveTextContent('devices.messaging.errors.wrongKey');

      click(unlock, 'devices.messaging.recover.submit');
      await waitFor(() => expect(within(unlock).getByRole('alert')).toHaveTextContent('broken'));
      expect(cryptoApi.registerDevice).not.toHaveBeenCalled();
    });

    test('can be closed without changing anything', async () => {
      const unlock = await openUnlock('devices.messaging.buttons.useRecoveryKey');

      click(unlock, 'devices.messaging.buttons.cancel');

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(cryptoApi.identityBackup).not.toHaveBeenCalled();
    });

    test('starting over warns first, then replaces the identity rather than adding one', async () => {
      renderPage();
      await waitFor(() => expect(within(panel()).getByText('devices.messaging.elsewhere')).toBeInTheDocument());
      click(panel(), 'devices.messaging.buttons.startOver');
      expect(within(dialog()).getByText('devices.messaging.reset.body')).toBeInTheDocument();

      click(dialog(), 'devices.messaging.buttons.startOver');
      choosePin();
      click(dialog(), 'devices.messaging.setup.finish');

      await waitFor(() => expect(cryptoApi.replaceIdentity).toHaveBeenCalledWith(expect.any(Uint8Array), null));
      expect(cryptoApi.createIdentity).not.toHaveBeenCalled();
      await waitFor(() => expect(cryptoApi.registerDevice).toHaveBeenCalled());
    });
  });

  describe('a browser that is set up', () => {
    beforeEach(() => {
      readLocalDevice.mockResolvedValue(local);
      cryptoApi.status.mockResolvedValue(status({ identity: identity(), pin: pin(), devices: [device()] }));
    });

    const ready = async () => {
      renderPage();
      await waitFor(() => expect(within(panel()).getByText('devices.messaging.ready')).toBeInTheDocument());
    };

    test('shows its key on the panel and its own session, and says it survives sign-out', async () => {
      await ready();

      expect(screen.getAllByText(FINGERPRINT)).toHaveLength(2);
      expect(screen.getAllByText('devices.fields.noMessagingKey')).toHaveLength(1);
      expect(within(panel()).getByText('devices.messaging.keepsKey')).toBeInTheDocument();
      expect(within(panel()).queryByText('devices.messaging.noRecoveryKey')).not.toBeInTheDocument();
      expect(within(panel()).queryByRole('button', { name: 'devices.messaging.buttons.addRecoveryKey' })).not.toBeInTheDocument();
      expect(mockVault.prepareKeyPackages).not.toHaveBeenCalled();
      expect(attachThisBrowser).not.toHaveBeenCalled();
    });

    test('changes the PIN', async () => {
      await ready();
      click(panel(), 'devices.messaging.buttons.changePin');

      choosePin('246810');
      click(dialog(), 'devices.messaging.buttons.save');

      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('devices.messaging.changePin.done'));
      expect(mockVault.blindPin).toHaveBeenCalledWith(5, '246810');
      expect(cryptoApi.setPin).toHaveBeenCalled();
    });

    test('a new PIN must be six digits and typed twice the same', async () => {
      await ready();
      click(panel(), 'devices.messaging.buttons.changePin');

      choosePin('24681');
      click(dialog(), 'devices.messaging.buttons.save');
      expect(within(dialog()).getByRole('alert')).toHaveTextContent('devices.messaging.pin.invalid');
      choosePin('246810', '246811');
      click(dialog(), 'devices.messaging.buttons.save');
      expect(within(dialog()).getByRole('alert')).toHaveTextContent('devices.messaging.pin.mismatch');
      expect(cryptoApi.setPin).not.toHaveBeenCalled();
    });

    test('an account without a PIN or a recovery key is offered both', async () => {
      cryptoApi.status.mockResolvedValue(status({ identity: identity({ recoveryKey: false }), pin: pin({ set: false }), devices: [device()] }));
      await ready();

      expect(within(panel()).getByText('devices.messaging.noRecoveryKey')).toBeInTheDocument();
      expect(within(panel()).getByRole('button', { name: 'devices.messaging.buttons.setPin' })).toBeInTheDocument();
      click(panel(), 'devices.messaging.buttons.addRecoveryKey');

      const save = within(dialog()).getByRole('button', { name: 'devices.messaging.buttons.save' });
      expect(save).toBeDisabled();
      confirmSaved(dialog());
      fireEvent.click(save);

      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('devices.messaging.addRecovery.done'));
      expect(cryptoApi.setRecoveryKey).toHaveBeenCalledWith({ wrappedPrivate: new Uint8Array([7]), signature: new Uint8Array(64) });
    });

    test('forgetting this browser removes its key from the account and from here', async () => {
      await ready();
      cryptoApi.status.mockResolvedValue(status({ identity: identity(), pin: pin(), devices: [] }));

      click(panel(), 'devices.messaging.buttons.forget');

      await waitFor(() => expect(cryptoApi.forgetDevice).toHaveBeenCalledWith(3));
      expect(wipeLocalKeys).toHaveBeenCalled();
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('devices.messaging.forgotten'));
      await waitFor(() => expect(within(panel()).getByText('devices.messaging.elsewhere')).toBeInTheDocument());
    });

    test('nothing is forgotten when the question is declined', async () => {
      window.confirm = jest.fn(() => false);
      await ready();

      click(panel(), 'devices.messaging.buttons.forget');

      expect(cryptoApi.forgetDevice).not.toHaveBeenCalled();
      expect(wipeLocalKeys).not.toHaveBeenCalled();
    });

    test('tops up its key packages when they run low, and carries on when that fails', async () => {
      const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
      cryptoApi.status.mockResolvedValue(status({ identity: identity(), pin: pin(), devices: [device({ keyPackages: 5 })] }));
      const packages = [{ ref: new Uint8Array([1]), data: new Uint8Array([2]), lastResort: false }];
      mockVault.prepareKeyPackages.mockResolvedValueOnce(packages);
      cryptoApi.uploadKeyPackages.mockRejectedValueOnce(new Error('offline'));

      await ready();

      await waitFor(() => expect(cryptoApi.uploadKeyPackages).toHaveBeenCalledWith(3, packages));
      expect(mockVault.prepareKeyPackages).toHaveBeenCalledWith(95);
      await waitFor(() => expect(warn).toHaveBeenCalledWith('[Messaging] Could not top up key packages:', 'offline'));
      warn.mockRestore();
    });

    test('a device still attached to an earlier sign-in is moved onto this one', async () => {
      cryptoApi.status.mockResolvedValue(status({ identity: identity(), pin: pin(), devices: [device({ sessionId: null })] }));

      await ready();

      await waitFor(() => expect(attachThisBrowser).toHaveBeenCalledWith(12, 5));
    });

    test('forgets its keys and says so once the server no longer lists its device', async () => {
      cryptoApi.status.mockResolvedValue(status({ identity: identity(), pin: pin(), devices: [device({ id: 4, sessionId: 13 })] }));

      renderPage();

      await waitFor(() => expect(within(panel()).getByText('devices.messaging.removed')).toBeInTheDocument());
      expect(wipeLocalKeys).toHaveBeenCalled();
    });

    test('keys another account left on this browser are not treated as this browser\'s', async () => {
      readLocalDevice.mockResolvedValue({ ...local, userId: 6 });

      renderPage();

      await waitFor(() => expect(wipeLocalKeys).toHaveBeenCalled());
      expect(within(panel()).queryByText('devices.messaging.ready')).not.toBeInTheDocument();
    });

    test('ending another session reads the messaging keys again', async () => {
      await ready();
      const before = cryptoApi.status.mock.calls.length;

      const other = screen.getByText('Firefox/1').closest('.device-item');
      fireEvent.click(within(other).getByRole('button', { name: 'devices.buttons.end' }));

      await waitFor(() => expect(cryptoApi.status.mock.calls.length).toBeGreaterThan(before));
    });
  });

  describe('browsers that are signed out but keep a key', () => {
    beforeEach(() => {
      cryptoApi.status.mockResolvedValue(status({
        identity: identity(), pin: pin(),
        devices: [device({ id: 9, sessionId: null, label: 'Old laptop' }), device({ id: 10, sessionId: null, label: null })]
      }));
    });

    test('are listed and can be removed', async () => {
      renderPage();
      await waitFor(() => expect(within(panel()).getByText('Old laptop')).toBeInTheDocument());
      expect(within(panel()).getByText('devices.fields.unknownDevice')).toBeInTheDocument();

      const laptop = within(panel()).getByText('Old laptop').closest('li');
      fireEvent.click(within(laptop).getByRole('button', { name: 'devices.messaging.buttons.remove' }));

      await waitFor(() => expect(cryptoApi.forgetDevice).toHaveBeenCalledWith(9));
      expect(wipeLocalKeys).not.toHaveBeenCalled();
      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('devices.messaging.signedOut.removed'));
    });

    test('a removal the server refuses says so', async () => {
      const error = jest.spyOn(console, 'error').mockImplementation(() => {});
      cryptoApi.forgetDevice.mockRejectedValue(new Error('down'));
      renderPage();
      await waitFor(() => expect(within(panel()).getByText('Old laptop')).toBeInTheDocument());

      const laptop = within(panel()).getByText('Old laptop').closest('li');
      fireEvent.click(within(laptop).getByRole('button', { name: 'devices.messaging.buttons.remove' }));

      await waitFor(() => expect(toast.error).toHaveBeenCalledWith('devices.messaging.errors.generic'));
      error.mockRestore();
    });
  });
});
