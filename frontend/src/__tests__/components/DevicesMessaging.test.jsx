import React from 'react';
import { render, screen, fireEvent, waitFor, within } from '../../test-utils/rtl';
import '@testing-library/jest-dom';
import { MemoryRouter } from 'react-router-dom';
import Devices from '../../components/Devices';

const translation = { t: (key) => key, i18n: { language: 'en' } };
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
    registerDevice: jest.fn(),
    uploadKeyPackages: jest.fn()
  }
}));

const mockVault = {
  createIdentity: jest.fn(),
  restoreIdentity: jest.fn(),
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

const { authService } = require('../../services/authService');
const { cryptoApi } = require('../../services/cryptoApi');
const { readLocalDevice, wipeLocalKeys } = require('../../crypto/keyStore');
const { VaultError } = require('../../crypto/client');
const { toast } = require('react-toastify');

const KEY = btoa(String.fromCharCode(...Array.from({ length: 32 }, (_, index) => index)));
const FINGERPRINT = '0001 0203 0405 0607 0809 0a0b 0c0d 0e0f';
const RECOVERY_KEY = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789ABCDEFGHJKLMNPQR';

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
  id: 3, sessionId: 12, signatureKey: KEY, createdAt: '2026-11-18T10:00:00Z', keyPackages: 100, ...overrides
});

const identity = { publicKey: KEY, createdAt: '2026-11-18T09:00:00Z' };
const prepared = { signatureKey: new Uint8Array(32), credential: new Uint8Array(4), identitySignature: new Uint8Array(64), keyPackages: [] };

const renderPage = () => render(<MemoryRouter><Devices /></MemoryRouter>);
const panel = () => screen.getByRole('region', { name: 'devices.messaging.title' });

const confirmSaved = (dialog) => {
  const groups = within(dialog).getByRole('group', { name: 'devices.messaging.setup.keyLabel' });
  const last = Array.from(groups.children).at(-1).textContent;
  fireEvent.click(within(dialog).getByRole('checkbox'));
  fireEvent.change(within(dialog).getByLabelText('devices.messaging.setup.checkLabel'), { target: { value: last.toLowerCase() } });
  return groups;
};

describe('Devices: encrypted messaging keys', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockSessionId = '12';
    window.confirm = jest.fn(() => true);
    authService.listDevices.mockResolvedValue([session(), session({ id: 13, userAgent: 'Firefox/1' })]);
    authService.endDevice.mockResolvedValue(undefined);
    cryptoApi.status.mockResolvedValue({ enabled: true, identity: null, devices: [] });
    readLocalDevice.mockResolvedValue(null);
    mockVault.createIdentity.mockResolvedValue({ publicKey: new Uint8Array(32), wrappedPrivate: new Uint8Array(60) });
    mockVault.prepareDevice.mockResolvedValue(prepared);
    mockVault.commitDevice.mockResolvedValue({ userId: 5, deviceId: 3, signatureKey: new Uint8Array(32) });
    cryptoApi.createIdentity.mockResolvedValue(identity);
    cryptoApi.replaceIdentity.mockResolvedValue(identity);
    cryptoApi.registerDevice.mockResolvedValue(device());
  });

  test('shows nothing about messaging while the feature is off', async () => {
    cryptoApi.status.mockResolvedValue({ enabled: false, identity: null, devices: [] });

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

    await waitFor(() => expect(screen.getByText('Firefox/1')).toBeInTheDocument());
    await waitFor(() => expect(cryptoApi.status).toHaveBeenCalled());
    expect(screen.queryByRole('region', { name: 'devices.messaging.title' })).not.toBeInTheDocument();
  });

  test('sets up a new account: saving the recovery key comes first, then the identity, then this browser', async () => {
    renderPage();
    await waitFor(() => expect(within(panel()).getByText('devices.messaging.notSetUp')).toBeInTheDocument());
    expect(screen.getAllByText('devices.fields.noMessagingKey')).toHaveLength(2);

    fireEvent.click(within(panel()).getByRole('button', { name: 'devices.messaging.buttons.setUp' }));
    const dialog = screen.getByRole('dialog');
    expect(within(dialog).getByText('devices.messaging.setup.introTitle')).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.buttons.setUp' }));

    const finish = within(dialog).getByRole('button', { name: 'devices.messaging.setup.finish' });
    expect(finish).toBeDisabled();
    const groups = confirmSaved(dialog);
    expect(groups.children).toHaveLength(12);
    const shown = Array.from(groups.children).map((group) => group.textContent).join('');

    cryptoApi.status.mockResolvedValue({ enabled: true, identity, devices: [device()] });
    readLocalDevice.mockResolvedValue({ userId: 5, deviceId: 3, signatureKey: new Uint8Array(32) });
    fireEvent.click(finish);

    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('devices.messaging.setup.done'));
    expect(mockVault.createIdentity).toHaveBeenCalledWith(shown);
    expect(cryptoApi.createIdentity).toHaveBeenCalled();
    expect(cryptoApi.replaceIdentity).not.toHaveBeenCalled();
    expect(mockVault.prepareDevice).toHaveBeenCalledWith(5);
    expect(cryptoApi.registerDevice).toHaveBeenCalledWith(12, prepared);
    expect(mockVault.commitDevice).toHaveBeenCalledWith(3);
    expect(cryptoApi.createIdentity.mock.invocationCallOrder[0])
      .toBeLessThan(cryptoApi.registerDevice.mock.invocationCallOrder[0]);
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    await waitFor(() => expect(within(panel()).getByText('devices.messaging.ready')).toBeInTheDocument());
    expect(within(panel()).getByText(FINGERPRINT)).toBeInTheDocument();
  });

  test('will not finish until the box is ticked and the last group is typed correctly', async () => {
    renderPage();
    await waitFor(() => expect(within(panel()).getByText('devices.messaging.notSetUp')).toBeInTheDocument());
    fireEvent.click(within(panel()).getByRole('button', { name: 'devices.messaging.buttons.setUp' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.buttons.setUp' }));
    const finish = within(dialog).getByRole('button', { name: 'devices.messaging.setup.finish' });

    fireEvent.change(within(dialog).getByLabelText('devices.messaging.setup.checkLabel'), { target: { value: 'ZZZZ' } });
    fireEvent.click(within(dialog).getByRole('checkbox'));
    expect(finish).toBeDisabled();
    fireEvent.submit(finish.closest('form'));

    expect(mockVault.createIdentity).not.toHaveBeenCalled();
  });

  test('keeps the dialog open and says why when the server refuses', async () => {
    cryptoApi.createIdentity.mockRejectedValue(new Error('errors.codes.IDENTITY_KEY_EXISTS'));
    renderPage();
    await waitFor(() => expect(within(panel()).getByText('devices.messaging.notSetUp')).toBeInTheDocument());
    fireEvent.click(within(panel()).getByRole('button', { name: 'devices.messaging.buttons.setUp' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.buttons.setUp' }));
    confirmSaved(dialog);

    fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.setup.finish' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('errors.codes.IDENTITY_KEY_EXISTS');
    expect(mockVault.prepareDevice).not.toHaveBeenCalled();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
  });

  test('refuses to register a browser whose session it cannot name', async () => {
    mockSessionId = null;
    renderPage();
    await waitFor(() => expect(within(panel()).getByText('devices.messaging.notSetUp')).toBeInTheDocument());
    fireEvent.click(within(panel()).getByRole('button', { name: 'devices.messaging.buttons.setUp' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.buttons.setUp' }));
    confirmSaved(dialog);

    fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.setup.finish' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent('devices.messaging.errors.noSession');
    expect(cryptoApi.registerDevice).not.toHaveBeenCalled();
  });

  test('copies and downloads the recovery key, and says so when copying fails', async () => {
    const writeText = jest.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('denied'));
    Object.assign(navigator, { clipboard: { writeText } });
    URL.createObjectURL = jest.fn(() => 'blob:key');
    URL.revokeObjectURL = jest.fn();
    const click = jest.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    renderPage();
    await waitFor(() => expect(within(panel()).getByText('devices.messaging.notSetUp')).toBeInTheDocument());
    fireEvent.click(within(panel()).getByRole('button', { name: 'devices.messaging.buttons.setUp' }));
    const dialog = screen.getByRole('dialog');
    fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.buttons.setUp' }));

    fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.setup.copy' }));
    await waitFor(() => expect(toast.success).toHaveBeenCalledWith('devices.messaging.setup.copied'));
    expect(writeText.mock.calls[0][0]).toMatch(/^([A-Z2-9]{4} ){11}[A-Z2-9]{4}$/);
    fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.setup.copy' }));
    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('devices.messaging.setup.copyFailed'));

    fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.setup.download' }));
    expect(click).toHaveBeenCalled();
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:key');
    click.mockRestore();
  });

  test('a cancelled setup creates nothing', async () => {
    renderPage();
    await waitFor(() => expect(within(panel()).getByText('devices.messaging.notSetUp')).toBeInTheDocument());
    fireEvent.click(within(panel()).getByRole('button', { name: 'devices.messaging.buttons.setUp' }));

    fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'devices.messaging.buttons.cancel' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(mockVault.createIdentity).not.toHaveBeenCalled();
    expect(cryptoApi.createIdentity).not.toHaveBeenCalled();
  });

  describe('an account set up elsewhere', () => {
    beforeEach(() => {
      cryptoApi.status.mockResolvedValue({ enabled: true, identity, devices: [device({ id: 8, sessionId: 13 })] });
      cryptoApi.identityBackup.mockResolvedValue({ publicKey: KEY, wrappedPrivate: btoa('wrapped') });
      mockVault.restoreIdentity.mockResolvedValue(undefined);
    });

    const openRecover = async () => {
      renderPage();
      await waitFor(() => expect(within(panel()).getByText('devices.messaging.elsewhere')).toBeInTheDocument());
      fireEvent.click(within(panel()).getByRole('button', { name: 'devices.messaging.buttons.useRecoveryKey' }));
      return screen.getByRole('dialog');
    };

    test('shows which other session holds a key', async () => {
      renderPage();
      await waitFor(() => expect(within(panel()).getByText('devices.messaging.elsewhere')).toBeInTheDocument());

      expect(screen.getByText(FINGERPRINT)).toBeInTheDocument();
      expect(screen.getAllByText('devices.fields.noMessagingKey')).toHaveLength(1);
    });

    test('adds this browser with the recovery key, however it was typed', async () => {
      const dialog = await openRecover();
      const typed = RECOVERY_KEY.toLowerCase().match(/.{4}/g).join('-');

      fireEvent.change(within(dialog).getByLabelText('devices.messaging.recover.label'), { target: { value: typed } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.recover.submit' }));

      await waitFor(() => expect(toast.success).toHaveBeenCalledWith('devices.messaging.setup.done'));
      expect(mockVault.restoreIdentity).toHaveBeenCalledWith(RECOVERY_KEY, {
        publicKey: expect.any(Uint8Array), wrappedPrivate: expect.any(Uint8Array)
      });
      expect(cryptoApi.registerDevice).toHaveBeenCalledWith(12, prepared);
      expect(cryptoApi.createIdentity).not.toHaveBeenCalled();
    });

    test('refuses a key that cannot be one without asking the server', async () => {
      const dialog = await openRecover();

      fireEvent.change(within(dialog).getByLabelText('devices.messaging.recover.label'), { target: { value: 'ABCD' } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.recover.submit' }));

      expect(await within(dialog).findByRole('alert')).toHaveTextContent('devices.messaging.errors.malformedKey');
      expect(cryptoApi.identityBackup).not.toHaveBeenCalled();
    });

    test('says plainly when the key belongs to another account', async () => {
      mockVault.restoreIdentity.mockRejectedValue(new VaultError('RecoveryKeyMismatchError', 'RecoveryKeyMismatch'));
      const dialog = await openRecover();

      fireEvent.change(within(dialog).getByLabelText('devices.messaging.recover.label'), { target: { value: RECOVERY_KEY } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.recover.submit' }));

      expect(await within(dialog).findByRole('alert')).toHaveTextContent('devices.messaging.errors.wrongKey');
      expect(cryptoApi.registerDevice).not.toHaveBeenCalled();
    });

    test('passes any other failure through', async () => {
      mockVault.restoreIdentity.mockRejectedValue(new VaultError('OperationError', 'broken'));
      const dialog = await openRecover();

      fireEvent.change(within(dialog).getByLabelText('devices.messaging.recover.label'), { target: { value: RECOVERY_KEY } });
      fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.recover.submit' }));

      expect(await within(dialog).findByRole('alert')).toHaveTextContent('broken');
    });

    test('can be closed without changing anything', async () => {
      const dialog = await openRecover();

      fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.buttons.cancel' }));

      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
      expect(cryptoApi.identityBackup).not.toHaveBeenCalled();
    });

    test('starting over warns first, then replaces the identity rather than adding one', async () => {
      renderPage();
      await waitFor(() => expect(within(panel()).getByText('devices.messaging.elsewhere')).toBeInTheDocument());
      fireEvent.click(within(panel()).getByRole('button', { name: 'devices.messaging.buttons.startOver' }));
      const dialog = screen.getByRole('dialog');
      expect(within(dialog).getByText('devices.messaging.reset.body')).toBeInTheDocument();

      fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.buttons.startOver' }));
      confirmSaved(dialog);
      fireEvent.click(within(dialog).getByRole('button', { name: 'devices.messaging.setup.finish' }));

      await waitFor(() => expect(cryptoApi.replaceIdentity).toHaveBeenCalled());
      expect(cryptoApi.createIdentity).not.toHaveBeenCalled();
      await waitFor(() => expect(cryptoApi.registerDevice).toHaveBeenCalled());
    });
  });

  describe('a browser that is set up', () => {
    beforeEach(() => {
      readLocalDevice.mockResolvedValue({ userId: 5, deviceId: 3, signatureKey: new Uint8Array(32) });
      cryptoApi.status.mockResolvedValue({ enabled: true, identity, devices: [device()] });
    });

    test('shows its key on the panel and on its own session only', async () => {
      renderPage();

      await waitFor(() => expect(within(panel()).getByText('devices.messaging.ready')).toBeInTheDocument());
      expect(screen.getAllByText(FINGERPRINT)).toHaveLength(2);
      expect(screen.getAllByText('devices.fields.noMessagingKey')).toHaveLength(1);
      expect(mockVault.prepareKeyPackages).not.toHaveBeenCalled();
    });

    test('tops up its key packages when they run low', async () => {
      cryptoApi.status.mockResolvedValue({ enabled: true, identity, devices: [device({ keyPackages: 5 })] });
      const packages = [{ ref: new Uint8Array([1]), data: new Uint8Array([2]), lastResort: false }];
      mockVault.prepareKeyPackages.mockResolvedValue(packages);
      cryptoApi.uploadKeyPackages.mockResolvedValue({ available: 100 });

      renderPage();

      await waitFor(() => expect(cryptoApi.uploadKeyPackages).toHaveBeenCalledWith(3, packages));
      expect(mockVault.prepareKeyPackages).toHaveBeenCalledWith(95);
    });

    test('carries on when a top-up fails', async () => {
      const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
      cryptoApi.status.mockResolvedValue({ enabled: true, identity, devices: [device({ keyPackages: 0 })] });
      mockVault.prepareKeyPackages.mockRejectedValue(new Error('no device'));

      renderPage();

      await waitFor(() => expect(warn).toHaveBeenCalledWith('[Messaging] Could not top up key packages:', 'no device'));
      expect(within(panel()).getByText('devices.messaging.ready')).toBeInTheDocument();
      warn.mockRestore();
    });

    test('forgets its keys and says so once the server no longer lists its device', async () => {
      cryptoApi.status.mockResolvedValue({ enabled: true, identity, devices: [device({ id: 4, sessionId: 13 })] });

      renderPage();

      await waitFor(() => expect(within(panel()).getByText('devices.messaging.removed')).toBeInTheDocument());
      expect(wipeLocalKeys).toHaveBeenCalled();
      expect(within(panel()).getByText('devices.messaging.elsewhere')).toBeInTheDocument();
    });

    test('ending another session reads the messaging keys again', async () => {
      renderPage();
      await waitFor(() => expect(within(panel()).getByText('devices.messaging.ready')).toBeInTheDocument());
      const before = cryptoApi.status.mock.calls.length;

      const other = screen.getByText('Firefox/1').closest('.device-item');
      fireEvent.click(within(other).getByRole('button', { name: 'devices.buttons.end' }));

      await waitFor(() => expect(cryptoApi.status.mock.calls.length).toBeGreaterThan(before));
    });
  });
});
