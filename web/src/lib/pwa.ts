/* Service worker registration and web push (FCM) for helpers. */
import { useEffect, useState } from 'react';
import { useEmulators, vapidKey } from './env';

export const SW_URL = '/firebase-messaging-sw.js';

export function registerServiceWorker(): void {
  if (import.meta.env.DEV || !('serviceWorker' in navigator)) return;
  window.addEventListener('load', () => {
    navigator.serviceWorker.register(SW_URL, { scope: '/' }).catch((e) => console.warn('Service worker not registered', e));
  });
}

export const isIOS = (): boolean =>
  /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

export const isStandalone = (): boolean =>
  window.matchMedia?.('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone === true;

export type PushSupport =
  | { kind: 'supported' }
  | { kind: 'ios-needs-home-screen' }
  | { kind: 'unsupported'; reason: string };

export function pushSupport(): PushSupport {
  if (typeof window === 'undefined') return { kind: 'unsupported', reason: 'Not in a browser.' };
  if (isIOS() && !isStandalone()) return { kind: 'ios-needs-home-screen' };
  if (!('Notification' in window) || !('serviceWorker' in navigator) || !('PushManager' in window)) {
    return { kind: 'unsupported', reason: 'This browser can’t show notifications from websites.' };
  }
  return { kind: 'supported' };
}

const TOKEN_KEY = 'gm.pushToken';

export function savedPushToken(): string | null {
  try {
    return localStorage.getItem(TOKEN_KEY);
  } catch {
    return null;
  }
}

/** Ask permission, get an FCM token for this browser, and return it. */
export async function getPushToken(): Promise<string> {
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') throw Object.assign(new Error('Notifications were not allowed. You can allow them in your browser settings.'), { code: 'permission-denied-push' });
  if (useEmulators) throw new Error('Web push needs a real Firebase project. It doesn’t work with the emulators.');
  if (!vapidKey) throw new Error('Web push isn’t configured yet (missing VITE_FIREBASE_VAPID_KEY).');
  const [{ getMessaging, getToken, isSupported }, { getServices }] = await Promise.all([import('firebase/messaging'), import('../data/firebaseApi')]);
  if (!(await isSupported())) throw new Error('This browser can’t receive push notifications.');
  const registration = (await navigator.serviceWorker.getRegistration(SW_URL)) ?? (await navigator.serviceWorker.register(SW_URL, { scope: '/' }));
  const token = await getToken(getMessaging(getServices().app), { vapidKey, serviceWorkerRegistration: registration });
  try {
    localStorage.setItem(TOKEN_KEY, token);
  } catch {
    /* ignore */
  }
  return token;
}

export function forgetPushToken(): void {
  try {
    localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* ignore */
  }
}

/** Current notification permission, kept fresh. */
export function useNotificationPermission(): NotificationPermission | 'unsupported' {
  const read = () => (typeof Notification === 'undefined' ? 'unsupported' : Notification.permission);
  const [perm, setPerm] = useState<NotificationPermission | 'unsupported'>(read);
  useEffect(() => {
    const onFocus = () => setPerm(read());
    window.addEventListener('focus', onFocus);
    return () => window.removeEventListener('focus', onFocus);
  }, []);
  return perm;
}
