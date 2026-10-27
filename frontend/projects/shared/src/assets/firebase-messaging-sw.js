/*
 * Service worker de Web Push (FCM) do Lucena Drive. Mínimo e sem dependências externas:
 * recebe a mensagem do FCM (protocolo Web Push), exibe a notificação e abre o app ao tocar.
 * Registrado com escopo próprio para não interferir no service worker do PWA (ngsw-worker.js).
 */
self.addEventListener('push', (event) => {
  let payload = {};
  try { payload = event.data ? event.data.json() : {}; } catch (e) { payload = {}; }
  const n = payload.notification || {};
  const data = payload.data || {};
  event.waitUntil(self.registration.showNotification(n.title || 'Lucena Drive', {
    body: n.body || '',
    icon: '/icons/icon-192x192.png',
    badge: '/icons/icon-72x72.png',
    tag: data.rideId ? 'ride-' + data.rideId : undefined,
    data,
  }));
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const target = new URL('/', self.location.origin).href;
  event.waitUntil(self.clients.matchAll({ type: 'window', includeUncontrolled: true }).then((list) => {
    for (const c of list) {
      if (c.url.startsWith(self.location.origin) && 'focus' in c) return c.focus();
    }
    return self.clients.openWindow(target);
  }));
});
