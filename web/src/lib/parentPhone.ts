/*
 * The parent's own phone number, for "Call Mom" links. DeviceConfig and the device doc
 * have no field for it (the phone never reports its number), so each helper saves it
 * on their own device. It never leaves this browser.
 */
import { useSyncExternalStore } from 'react';

const key = (deviceId: string) => `gm.parentPhone.${deviceId}`;
const listeners = new Set<() => void>();

function read(deviceId: string): string {
  try {
    return localStorage.getItem(key(deviceId)) ?? '';
  } catch {
    return '';
  }
}

export function setParentPhone(deviceId: string, phone: string) {
  try {
    if (phone.trim()) localStorage.setItem(key(deviceId), phone.trim());
    else localStorage.removeItem(key(deviceId));
  } catch {
    /* storage blocked: nothing to remember */
  }
  listeners.forEach((l) => l());
}

export function useParentPhone(deviceId: string): string {
  return useSyncExternalStore(
    (cb) => {
      listeners.add(cb);
      window.addEventListener('storage', cb);
      return () => {
        listeners.delete(cb);
        window.removeEventListener('storage', cb);
      };
    },
    () => read(deviceId),
    () => '',
  );
}

export function telHref(phone: string): string {
  return `tel:${phone.replace(/[^\d+]/g, '')}`;
}
