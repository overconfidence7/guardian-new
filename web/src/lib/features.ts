/*
 * Plain-language names for features and phone settings. Keys match core/Transparency.kt
 * and firebase/functions/src/copy.ts (FEATURE_LABELS, PERMISSION_LABELS).
 */

export const FEATURE_LABELS: Readonly<Record<string, string>> = {
  pause: 'the pause before sending money',
  remoteAccess: 'the remote-control app warning',
  longCall: 'long-call notices',
  screening: 'unknown caller screening',
  safeWord: 'the family safe word card',
  checkIn: 'the daily “I’m OK”',
  help: 'the Help button',
  reminders: 'reminders',
  all: 'Guardian Mode',
};

export const PERMISSION_LABELS: Readonly<Record<string, { name: string; effect: string; short: string }>> = {
  usageAccess: { name: 'Usage access', effect: 'pause cards can’t appear', short: 'Pause cards can’t appear' },
  overlay: { name: 'Display over other apps', effect: 'pause cards may not appear on top', short: 'Pause cards may not show on top' },
  callScreening: { name: 'Call screening', effect: 'unknown callers will ring as usual', short: 'Screening turned off' },
  phoneState: { name: 'Phone calls', effect: 'Guardian can’t tell when a call is going on', short: 'Can’t tell when a call is on' },
  notifications: { name: 'Notifications', effect: 'reminders and Guardian’s notice can’t show', short: 'Notifications are off' },
  exactAlarms: { name: 'Alarms and reminders', effect: 'reminders may arrive late', short: 'Reminders may be late' },
  fullScreenIntent: { name: 'Full-screen notifications', effect: 'reminder cards may show as small notifications', short: 'Reminder cards may be small' },
  battery: { name: 'Unrestricted battery', effect: 'the phone may stop Guardian in the background', short: 'Battery saver may pause Guardian' },
  homeApp: { name: 'Home app', effect: 'the simple home screen isn’t showing', short: 'Simple home screen isn’t on' },
  contacts: { name: 'Contacts', effect: 'calls from the phone’s own contacts may be treated as unknown', short: 'Can’t see the phone’s contacts' },
};

/** "camelCase", "SNAKE_CASE" -> "camel case" */
export function humanize(raw: string): string {
  return raw
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replace(/[_\-.]+/g, ' ')
    .trim()
    .toLowerCase();
}

export function featureLabel(key: string): string {
  return FEATURE_LABELS[key] ?? 'a Guardian Mode feature';
}

export function permissionLabel(key: string) {
  return PERMISSION_LABELS[key];
}

/** What a `status.missing` entry means, in a few words plus how to fix it. */
export function missingSetting(key: string): { short: string; fix: string } {
  const known = PERMISSION_LABELS[key];
  if (known) return { short: known.short, fix: `Turn on “${known.name}” for Guardian Mode in the phone’s Settings.` };
  const words = humanize(key);
  return { short: `${capitalize(words)} needs attention`, fix: 'Open Guardian Mode on the phone to fix it.' };
}

function capitalize(s: string) {
  return s ? s[0]!.toUpperCase() + s.slice(1) : s;
}
