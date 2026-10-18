/** Turns a device doc into the one-line state a helper sees on the family home. */
import type { Device } from './types';
import { missingSetting } from './features';

export type DeviceHealth =
  | { kind: 'not-set-up'; label: string; detail: string }
  | { kind: 'protected'; label: string; detail: string }
  | { kind: 'attention'; label: string; detail: string; items: { short: string; fix: string }[] }
  | { kind: 'quiet'; label: string; detail: string };

const DAY = 24 * 60 * 60_000;

export function deviceHealth(device: Device, now: number = Date.now()): DeviceHealth {
  if (!device.deviceUid) {
    return { kind: 'not-set-up', label: 'Not set up yet', detail: 'Use the setup code on the phone to connect it.' };
  }
  const status = device.status;
  const last = status?.lastHeartbeatAt ?? status?.lastSeenAt ?? null;
  const missing = status?.missing ?? [];
  if (!status || status.protected === null) {
    return { kind: 'quiet', label: 'Finishing setup', detail: 'The phone is connected. Protection starts once each feature is switched on there.' };
  }
  if (missing.length > 0 || status.protected === false) {
    const items = missing.map(missingSetting);
    const unique = items.filter((it, i) => items.findIndex((o) => o.short === it.short) === i);
    return {
      kind: 'attention',
      label: 'Needs attention',
      detail: unique.length > 0 ? unique.map((u) => u.short).join(' · ') : 'Protection isn’t fully on.',
      items: unique,
    };
  }
  if (last != null && now - last > DAY) {
    return { kind: 'quiet', label: 'Quiet for a while', detail: 'The phone hasn’t checked in for over a day.' };
  }
  return { kind: 'protected', label: 'Protected', detail: 'Everything is switched on and working.' };
}
