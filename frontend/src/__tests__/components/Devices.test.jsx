import React from 'react';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import '@testing-library/jest-dom';
import Devices from '../../components/Devices';

// `t` and `i18n` are stable references in react-i18next, and the component's loader depends on
// `t` — a fresh function per render here would re-fire the effect on every state change.
const translation = { t: (key) => key, i18n: { language: 'en' } };
jest.mock('react-i18next', () => ({
  useTranslation: () => translation
}));

const mockLogout = jest.fn();
jest.mock('../../context/AuthContext', () => ({
  useAuth: () => ({ logout: mockLogout })
}));

jest.mock('../../services/authService', () => ({
  authService: {
    listDevices: jest.fn(),
    endDevice: jest.fn()
  }
}));

let mockSessionId = '12';
jest.mock('../../services/session', () => ({
  getSessionId: () => mockSessionId
}));

jest.mock('react-toastify', () => ({
  toast: { success: jest.fn(), error: jest.fn() }
}));

const { authService } = require('../../services/authService');
const { toast } = require('react-toastify');

const session = (overrides = {}) => ({
  id: 12,
  ipAddress: '203.0.113.7',
  userAgent: 'Mozilla/5.0 (Macintosh)',
  signedInAt: '2026-08-01T09:00:00Z',
  lastSeenAt: '2026-09-02T11:30:00Z',
  expiresAt: '2026-10-02T11:30:00Z',
  ...overrides
});

describe('Devices', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockSessionId = '12';
    window.confirm = jest.fn(() => true);
    authService.listDevices.mockResolvedValue([]);
    authService.endDevice.mockResolvedValue(undefined);
  });

  test('lists every live session with what identifies it', async () => {
    authService.listDevices.mockResolvedValue([
      session(),
      session({ id: 13, userAgent: 'Firefox/1', ipAddress: '198.51.100.4' })
    ]);

    render(<Devices />);

    await waitFor(() => expect(screen.getByText('Mozilla/5.0 (Macintosh)')).toBeInTheDocument());
    expect(screen.getByText('Firefox/1')).toBeInTheDocument();
    expect(screen.getByText('203.0.113.7')).toBeInTheDocument();
    expect(screen.getByText('198.51.100.4')).toBeInTheDocument();
  });

  test('marks the row whose id matches the stored session, and only that one', async () => {
    authService.listDevices.mockResolvedValue([session(), session({ id: 13 })]);

    const { container } = render(<Devices />);

    await waitFor(() => expect(container.querySelectorAll('.device-item')).toHaveLength(2));
    expect(container.querySelectorAll('.device-current')).toHaveLength(1);
    expect(container.querySelector('.device-current')).toHaveAttribute('data-session-id', '12');
  });

  test('a session with no device details renders rather than blanking the row', async () => {
    authService.listDevices.mockResolvedValue([
      session({ id: 13, userAgent: null, ipAddress: null })
    ]);

    render(<Devices />);

    await waitFor(() =>
      expect(screen.getByText('devices.fields.unknownDevice')).toBeInTheDocument());
  });

  test('ending another session withdraws it by id and reloads the list', async () => {
    authService.listDevices.mockResolvedValue([session({ id: 13 })]);

    render(<Devices />);
    await waitFor(() => expect(screen.getByText('devices.buttons.end')).toBeInTheDocument());
    fireEvent.click(screen.getByText('devices.buttons.end'));

    await waitFor(() => expect(authService.endDevice).toHaveBeenCalledWith(13));
    expect(mockLogout).not.toHaveBeenCalled();
    expect(toast.success).toHaveBeenCalledWith('devices.messages.ended');
    await waitFor(() => expect(authService.listDevices).toHaveBeenCalledTimes(2));
  });

  test('ending your own session signs you out rather than leaving a dead page behind', async () => {
    authService.listDevices.mockResolvedValue([session()]);

    render(<Devices />);
    await waitFor(() => expect(screen.getByText('devices.buttons.end')).toBeInTheDocument());
    fireEvent.click(screen.getByText('devices.buttons.end'));

    await waitFor(() => expect(mockLogout).toHaveBeenCalled());
    expect(window.confirm).toHaveBeenCalledWith('devices.messages.endCurrentConfirm');
    expect(toast.success).not.toHaveBeenCalled();
  });

  test('declining the confirmation ends nothing', async () => {
    window.confirm = jest.fn(() => false);
    authService.listDevices.mockResolvedValue([session({ id: 13 })]);

    render(<Devices />);
    await waitFor(() => expect(screen.getByText('devices.buttons.end')).toBeInTheDocument());
    fireEvent.click(screen.getByText('devices.buttons.end'));

    expect(authService.endDevice).not.toHaveBeenCalled();
  });

  test('a refused revoke is reported and the list is left as it was', async () => {
    authService.listDevices.mockResolvedValue([session({ id: 13 })]);
    authService.endDevice.mockRejectedValue(new Error('404'));
    jest.spyOn(console, 'error').mockImplementation(() => {});

    render(<Devices />);
    await waitFor(() => expect(screen.getByText('devices.buttons.end')).toBeInTheDocument());
    fireEvent.click(screen.getByText('devices.buttons.end'));

    await waitFor(() => expect(toast.error).toHaveBeenCalledWith('devices.messages.endError'));
    expect(authService.listDevices).toHaveBeenCalledTimes(1);
  });

  test('a listing that fails says so instead of rendering an empty account', async () => {
    authService.listDevices.mockRejectedValue(new Error('500'));
    jest.spyOn(console, 'error').mockImplementation(() => {});

    render(<Devices />);

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('devices.messages.loadError'));
  });
});
