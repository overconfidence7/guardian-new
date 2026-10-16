/// <reference lib="webworker" />
/*
 * The dashboard's only service worker (registered at /firebase-messaging-sw.js):
 *  - precaches the app shell so the PWA opens instantly and offline;
 *  - receives web push from FCM and shows calm notifications;
 *  - focuses or opens the dashboard when a notification is tapped.
 */
import { initializeApp } from 'firebase/app';
import { getMessaging, onBackgroundMessage } from 'firebase/messaging/sw';
import { cleanupOutdatedCaches, createHandlerBoundToURL, precacheAndRoute } from 'workbox-precaching';
import { NavigationRoute, registerRoute } from 'workbox-routing';
import { CacheFirst } from 'workbox-strategies';
import { firebaseConfig, useEmulators } from './lib/env';

declare const self: ServiceWorkerGlobalScope & { __WB_MANIFEST: Array<{ url: string; revision: string | null }> };

self.skipWaiting();
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

precacheAndRoute(self.__WB_MANIFEST);
cleanupOutdatedCaches();
registerRoute(new NavigationRoute(createHandlerBoundToURL('/index.html'), { denylist: [/^\/__/, /\/[^/?]+\.[^/]+$/] }));
registerRoute(({ request }) => request.destination === 'font', new CacheFirst({ cacheName: 'fonts' }));

interface PushData {
  title?: string;
  body?: string;
  familyId?: string;
  deviceId?: string;
  severity?: string;
  url?: string;
}

if (!useEmulators) {
  try {
    const app = initializeApp(firebaseConfig);
    const messaging = getMessaging(app);
    // Messages with a `notification` payload are shown by the SDK automatically; this
    // handles data-only messages so they still show calmly.
    onBackgroundMessage(messaging, (payload) => {
      if (payload.notification) return;
      const data = (payload.data ?? {}) as PushData;
      if (!data.title && !data.body) return;
      const url = data.url ?? (data.familyId && data.deviceId ? `/f/${data.familyId}/d/${data.deviceId}/alerts` : '/');
      void self.registration.showNotification(data.title ?? 'Guardian Mode', {
        body: data.body,
        icon: '/icons/icon-192.png',
        badge: '/icons/badge-96.png',
        tag: data.deviceId ? `gm-${data.deviceId}` : undefined,
        requireInteraction: data.severity === 'URGENT',
        data: { url },
      });
    });
  } catch (e) {
    console.warn('Messaging unavailable in service worker', e);
  }
}

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const fcm = (event.notification.data as { FCM_MSG?: { data?: PushData; fcmOptions?: { link?: string } }; url?: string } | null) ?? {};
  const data = fcm.FCM_MSG?.data;
  const target =
    fcm.url ?? fcm.FCM_MSG?.fcmOptions?.link ?? (data?.familyId && data?.deviceId ? `/f/${data.familyId}/d/${data.deviceId}/alerts` : '/');
  event.waitUntil(
    (async () => {
      const url = new URL(target, self.location.origin).href;
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      for (const client of windows) {
        if ('focus' in client) {
          await client.focus();
          if ('navigate' in client) await (client as WindowClient).navigate(url);
          return;
        }
      }
      await self.clients.openWindow(url);
    })(),
  );
});
