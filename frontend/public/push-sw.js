const params = new URL(self.location.href).searchParams;
const TITLE = params.get('title') || 'Kanban';
const BODY = params.get('body') || '';

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('push', (event) => {
  let data = {};
  try {
    data = event.data ? event.data.json() : {};
  } catch {
    data = {};
  }
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    if (windows.some((client) => client.visibilityState === 'visible')) return;
    await self.registration.showNotification(TITLE, {
      body: BODY,
      icon: '/icon.svg',
      tag: data.c ? `conversation-${data.c}` : 'messages',
      renotify: true,
      data: { c: data.c ?? null },
    });
  })());
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const conversation = event.notification.data && event.notification.data.c;
  const target = conversation ? `/messages?c=${encodeURIComponent(conversation)}` : '/messages';
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    for (const client of windows) {
      if ('focus' in client) {
        if ('navigate' in client) await client.navigate(target);
        return client.focus();
      }
    }
    return self.clients.openWindow(target);
  })());
});
