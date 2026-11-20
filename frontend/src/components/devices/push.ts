import { Capacitor } from '@capacitor/core';
import { pushApi, type PushConfig } from '../../services/pushApi';

const STORED = 'kanban.pushEndpoint';

export type PushSupport = 'native' | 'web' | 'none';

export class PushBlockedError extends Error {
  constructor() {
    super('PushBlocked');
    this.name = 'PushBlockedError';
  }
}

export class PushUnavailableError extends Error {
  constructor() {
    super('PushUnavailable');
    this.name = 'PushUnavailableError';
  }
}

export interface NoticeText {
  title: string;
  body: string;
}

export function pushSupport(): PushSupport {
  if (Capacitor.isNativePlatform()) return 'native';
  return 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window ? 'web' : 'none';
}

const remember = (id: number | null) => {
  try {
    if (id === null) localStorage.removeItem(STORED); else localStorage.setItem(STORED, String(id));
  } catch {
    // a browser that refuses storage just forgets which endpoint it registered
  }
};

const remembered = (): number | null => {
  try {
    const id = Number(localStorage.getItem(STORED));
    return Number.isInteger(id) && id > 0 ? id : null;
  } catch {
    return null;
  }
};

const urlBase64 = (text: string): Uint8Array<ArrayBuffer> => {
  const padded = text.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (text.length % 4)) % 4);
  const raw = atob(padded);
  return Uint8Array.from(raw, (char) => char.charCodeAt(0));
};

const workerUrl = (notice: NoticeText) =>
  `/push-sw.js?${new URLSearchParams({ title: notice.title, body: notice.body }).toString()}`;

export async function pushEnabled(): Promise<boolean> {
  if (remembered() === null) return false;
  const support = pushSupport();
  if (support === 'native') return true;
  if (support === 'none' || Notification.permission !== 'granted') return false;
  const registration = await navigator.serviceWorker.getRegistration();
  return Boolean(await registration?.pushManager.getSubscription());
}

async function enableWeb(deviceId: number, config: PushConfig, notice: NoticeText): Promise<void> {
  if (!config.webPushKey) throw new PushUnavailableError();
  if (await Notification.requestPermission() !== 'granted') throw new PushBlockedError();
  const registration = await navigator.serviceWorker.register(workerUrl(notice));
  await navigator.serviceWorker.ready;
  const subscription = await registration.pushManager.subscribe({
    userVisibleOnly: true,
    applicationServerKey: urlBase64(config.webPushKey),
  });
  const keys = subscription.toJSON().keys;
  const stored = await pushApi.register({
    deviceId, kind: 'WEBPUSH', endpoint: subscription.endpoint, p256dh: keys?.p256dh, auth: keys?.auth,
  });
  remember(stored.id);
}

async function enableNative(deviceId: number, config: PushConfig): Promise<void> {
  if (!config.fcm) throw new PushUnavailableError();
  const { PushNotifications } = await import('@capacitor/push-notifications');
  const permission = await PushNotifications.requestPermissions();
  if (permission.receive !== 'granted') throw new PushBlockedError();
  const handles = [] as { remove: () => Promise<void> }[];
  try {
    const token = await new Promise<string>((resolve, reject) => {
      void PushNotifications.addListener('registration', (value) => resolve(value.value)).then((handle) => handles.push(handle));
      void PushNotifications.addListener('registrationError', (failure) => reject(new Error(failure.error)))
        .then((handle) => handles.push(handle));
      void PushNotifications.register();
    });
    const stored = await pushApi.register({ deviceId, kind: 'FCM', endpoint: token });
    remember(stored.id);
  } finally {
    await Promise.all(handles.map((handle) => handle.remove()));
  }
}

export async function enablePush(deviceId: number, config: PushConfig, notice: NoticeText): Promise<void> {
  const support = pushSupport();
  if (support === 'native') return enableNative(deviceId, config);
  if (support === 'web') return enableWeb(deviceId, config, notice);
  throw new PushUnavailableError();
}

export async function disablePush(): Promise<void> {
  const id = remembered();
  const support = pushSupport();
  if (support === 'web') {
    const registration = await navigator.serviceWorker.getRegistration();
    await (await registration?.pushManager.getSubscription())?.unsubscribe();
  } else if (support === 'native') {
    const { PushNotifications } = await import('@capacitor/push-notifications');
    await PushNotifications.unregister();
  }
  if (id !== null) await pushApi.remove(id);
  remember(null);
}

export async function openFromNotification(open: (conversationId: number) => void): Promise<() => void> {
  if (pushSupport() !== 'native') return () => undefined;
  const { PushNotifications } = await import('@capacitor/push-notifications');
  const handle = await PushNotifications.addListener('pushNotificationActionPerformed', (action) => {
    const conversationId = Number(action.notification.data?.conversationId);
    if (Number.isInteger(conversationId) && conversationId > 0) open(conversationId);
  });
  return () => void handle.remove();
}
