import {
  disablePush,
  enablePush,
  openFromNotification,
  pushEnabled,
  pushSupport,
  PushBlockedError,
  PushUnavailableError,
} from '../../components/devices/push';
import { pushApi } from '../../services/pushApi';

const mockNative = { value: false };
jest.mock('@capacitor/core', () => ({ Capacitor: { isNativePlatform: () => mockNative.value } }));
jest.mock('../../services/pushApi', () => ({ pushApi: { register: jest.fn(), remove: jest.fn(), config: jest.fn() } }));

const mockListeners = new Map<string, (value: unknown) => void>();
const mockPlugin = {
  requestPermissions: jest.fn(),
  register: jest.fn(),
  unregister: jest.fn(),
  addListener: jest.fn(async (name: string, callback: (value: unknown) => void) => {
    mockListeners.set(name, callback);
    return { remove: jest.fn(async () => { mockListeners.delete(name); }) };
  }),
};
jest.mock('@capacitor/push-notifications', () => ({ PushNotifications: mockPlugin }));

const api = pushApi as unknown as { register: jest.Mock; remove: jest.Mock };
const NOTICE = { title: 'New message', body: 'Open Kanban' };
const KEY = btoa(String.fromCharCode(...new Uint8Array(65).fill(4))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

let subscription: { endpoint: string; toJSON: () => unknown; unsubscribe: jest.Mock } | null;
let permission: string;
const registration = {
  pushManager: {
    subscribe: jest.fn(async () => subscription),
    getSubscription: jest.fn(async () => subscription),
  },
};

beforeEach(() => {
  jest.clearAllMocks();
  mockNative.value = false;
  mockListeners.clear();
  localStorage.clear();
  subscription = {
    endpoint: 'push-endpoint-1',
    toJSON: () => ({ keys: { p256dh: 'P', auth: 'A' } }),
    unsubscribe: jest.fn(async () => true),
  };
  permission = 'granted';
  Object.defineProperty(navigator, 'serviceWorker', {
    configurable: true,
    value: {
      register: jest.fn(async () => registration),
      ready: Promise.resolve(registration),
      getRegistration: jest.fn(async () => registration),
    },
  });
  (window as unknown as { PushManager: unknown }).PushManager = class {};
  (window as unknown as { Notification: unknown }).Notification = {
    get permission() { return permission; },
    requestPermission: jest.fn(async () => permission),
  };
});

describe('push on the web', () => {
  test('turning on asks for permission, registers the worker with the translated text and sends the subscription', async () => {
    api.register.mockResolvedValue({ id: 31 });

    await enablePush(7, { webPushKey: KEY, fcm: false }, NOTICE);

    const url = (navigator.serviceWorker.register as jest.Mock).mock.calls[0][0] as string;
    expect(url).toBe('/push-sw.js?title=New+message&body=Open+Kanban');
    expect(registration.pushManager.subscribe).toHaveBeenCalledWith(
      expect.objectContaining({ userVisibleOnly: true, applicationServerKey: expect.any(Uint8Array) }));
    expect(api.register).toHaveBeenCalledWith({
      deviceId: 7, kind: 'WEBPUSH', endpoint: 'push-endpoint-1', p256dh: 'P', auth: 'A',
    });
    expect(await pushEnabled()).toBe(true);
  });

  test('a refused permission is reported and nothing is registered', async () => {
    permission = 'denied';

    await expect(enablePush(7, { webPushKey: KEY, fcm: false }, NOTICE)).rejects.toBeInstanceOf(PushBlockedError);

    expect(api.register).not.toHaveBeenCalled();
    expect(await pushEnabled()).toBe(false);
  });

  test('a server without a Web Push key offers nothing', async () => {
    await expect(enablePush(7, { webPushKey: null, fcm: false }, NOTICE)).rejects.toBeInstanceOf(PushUnavailableError);
  });

  test('turning off unsubscribes the browser and tells the server which endpoint to forget', async () => {
    api.register.mockResolvedValue({ id: 31 });
    await enablePush(7, { webPushKey: KEY, fcm: false }, NOTICE);

    await disablePush();

    expect(subscription?.unsubscribe).toHaveBeenCalled();
    expect(api.remove).toHaveBeenCalledWith(31);
    expect(await pushEnabled()).toBe(false);
  });

  test('it counts as off when permission was withdrawn or the subscription is gone', async () => {
    api.register.mockResolvedValue({ id: 31 });
    await enablePush(7, { webPushKey: KEY, fcm: false }, NOTICE);

    permission = 'denied';
    expect(await pushEnabled()).toBe(false);
    permission = 'granted';
    subscription = null;
    expect(await pushEnabled()).toBe(false);
  });

  test('turning off with nothing remembered only unsubscribes', async () => {
    await disablePush();

    expect(api.remove).not.toHaveBeenCalled();
  });

  test('a browser without service workers or Push is reported as unsupported', async () => {
    delete (window as unknown as { PushManager?: unknown }).PushManager;

    expect(pushSupport()).toBe('none');
    await expect(enablePush(7, { webPushKey: KEY, fcm: true }, NOTICE)).rejects.toBeInstanceOf(PushUnavailableError);
    localStorage.setItem('kanban.pushEndpoint', '5');
    expect(await pushEnabled()).toBe(false);
  });

  test('a browser that refuses storage still works', async () => {
    api.register.mockResolvedValue({ id: 31 });
    const spy = jest.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('full'); });
    const read = jest.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked'); });

    await enablePush(7, { webPushKey: KEY, fcm: false }, NOTICE);
    expect(await pushEnabled()).toBe(false);

    spy.mockRestore();
    read.mockRestore();
  });
});

describe('push in the app', () => {
  beforeEach(() => {
    mockNative.value = true;
  });

  test('turning on asks the system, waits for the token and registers it', async () => {
    mockPlugin.requestPermissions.mockResolvedValue({ receive: 'granted' });
    mockPlugin.register.mockImplementation(async () => { mockListeners.get('registration')?.({ value: 'fcm-token' }); });
    api.register.mockResolvedValue({ id: 8 });

    await enablePush(7, { webPushKey: null, fcm: true }, NOTICE);

    expect(api.register).toHaveBeenCalledWith({ deviceId: 7, kind: 'FCM', endpoint: 'fcm-token' });
    expect(mockListeners.size).toBe(0);
    expect(await pushEnabled()).toBe(true);
  });

  test('a refused permission, a failed registration and a server without FCM are all reported', async () => {
    mockPlugin.requestPermissions.mockResolvedValueOnce({ receive: 'denied' });
    await expect(enablePush(7, { webPushKey: null, fcm: true }, NOTICE)).rejects.toBeInstanceOf(PushBlockedError);

    mockPlugin.requestPermissions.mockResolvedValueOnce({ receive: 'granted' });
    mockPlugin.register.mockImplementationOnce(async () => { mockListeners.get('registrationError')?.({ error: 'no play services' }); });
    await expect(enablePush(7, { webPushKey: null, fcm: true }, NOTICE)).rejects.toThrow('no play services');
    expect(mockListeners.size).toBe(0);

    await expect(enablePush(7, { webPushKey: null, fcm: false }, NOTICE)).rejects.toBeInstanceOf(PushUnavailableError);
  });

  test('turning off unregisters the app and the endpoint', async () => {
    localStorage.setItem('kanban.pushEndpoint', '8');

    await disablePush();

    expect(mockPlugin.unregister).toHaveBeenCalled();
    expect(api.remove).toHaveBeenCalledWith(8);
  });

  test('tapping a notification opens its conversation, and only a real id does', async () => {
    const open = jest.fn();
    const stop = await openFromNotification(open);

    mockListeners.get('pushNotificationActionPerformed')?.({ notification: { data: { conversationId: '12' } } });
    mockListeners.get('pushNotificationActionPerformed')?.({ notification: { data: { conversationId: 'x' } } });
    mockListeners.get('pushNotificationActionPerformed')?.({ notification: { data: undefined } });

    expect(open).toHaveBeenCalledTimes(1);
    expect(open).toHaveBeenCalledWith(12);
    stop();
  });

  test('on the web there is nothing to listen to', async () => {
    mockNative.value = false;

    const stop = await openFromNotification(jest.fn());
    stop();

    expect(mockPlugin.addListener).not.toHaveBeenCalled();
  });
});
