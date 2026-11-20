import { fireEvent, render, screen, waitFor } from '../../test-utils/rtl';
import '@testing-library/jest-dom';
import MessagingPush from '../../components/devices/MessagingPush';
import { disablePush, enablePush, pushEnabled, pushSupport, PushBlockedError } from '../../components/devices/push';
import { pushApi } from '../../services/pushApi';

jest.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key) => key, i18n: { language: 'en' } }) }));
jest.mock('../../services/pushApi', () => ({ pushApi: { config: jest.fn() } }));
jest.mock('../../components/devices/push', () => {
  class PushBlockedError extends Error {}
  return { pushSupport: jest.fn(), pushEnabled: jest.fn(), enablePush: jest.fn(), disablePush: jest.fn(), PushBlockedError };
});

beforeEach(() => {
  jest.clearAllMocks();
  pushSupport.mockReturnValue('web');
  pushEnabled.mockResolvedValue(false);
  pushApi.config.mockResolvedValue({ webPushKey: 'key', fcm: false });
});

describe('notifications on the devices page', () => {
  test('offers to turn them on and passes the translated notice to the worker', async () => {
    enablePush.mockResolvedValue();
    render(<MessagingPush deviceId={7} />);

    fireEvent.click(await screen.findByRole('button', { name: 'devices.messaging.push.enable' }));

    await waitFor(() => expect(enablePush).toHaveBeenCalledWith(7, { webPushKey: 'key', fcm: false }, {
      title: 'devices.messaging.push.noticeTitle', body: 'devices.messaging.push.noticeBody',
    }));
  });

  test('shows that they are on and turns them off', async () => {
    pushEnabled.mockResolvedValue(true);
    disablePush.mockResolvedValue();
    render(<MessagingPush deviceId={7} />);

    expect(await screen.findByText('devices.messaging.push.on')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'devices.messaging.push.disable' }));

    await waitFor(() => expect(disablePush).toHaveBeenCalled());
  });

  test('explains a blocked permission and any other failure', async () => {
    enablePush.mockRejectedValueOnce(new PushBlockedError()).mockRejectedValueOnce(new Error('boom'));
    render(<MessagingPush deviceId={7} />);
    const button = await screen.findByRole('button', { name: 'devices.messaging.push.enable' });

    fireEvent.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent('devices.messaging.push.blocked');
    fireEvent.click(button);
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('devices.messaging.push.failed'));
  });

  test('says so when the browser cannot show them, and shows nothing when the server offers none', async () => {
    pushSupport.mockReturnValue('none');
    const { unmount } = render(<MessagingPush deviceId={7} />);
    expect(screen.getByText('devices.messaging.push.unsupported')).toBeInTheDocument();
    unmount();

    pushSupport.mockReturnValue('web');
    pushApi.config.mockResolvedValue({ webPushKey: null, fcm: true });
    const { container } = render(<MessagingPush deviceId={7} />);
    await waitFor(() => expect(pushApi.config).toHaveBeenCalled());
    expect(container).toBeEmptyDOMElement();
  });

  test('in the app it is the FCM setting that decides', async () => {
    pushSupport.mockReturnValue('native');
    pushApi.config.mockResolvedValue({ webPushKey: null, fcm: true });

    render(<MessagingPush deviceId={7} />);

    expect(await screen.findByRole('button', { name: 'devices.messaging.push.enable' })).toBeInTheDocument();
  });
});
