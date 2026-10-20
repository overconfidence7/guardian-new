/*
 * Changes only the parent can make (docs/SECURITY.md §3). Once a phone is paired, the
 * rules refuse a helper's config write that changes the family's numbers or primary
 * helper, trusts a remote-control app, turns off pause cards, the remote-control warning
 * or long-call notices, or unlocks the phone remotely. The dashboard saves the rest of an
 * edit at once and sends these as a request the parent answers on the phone.
 * Mirrors keepsParentOnlySettings in firebase/firestore.rules and
 * firebase/functions/src/lib/changeRequests.ts.
 */
import type { DeviceConfig, FamilyContact } from './model';

export interface RiskyChanges {
  family?: FamilyContact[];
  trustedPackages?: string[];
  pauseTripwiresEnabled?: boolean;
  remoteAccessAlarmEnabled?: boolean;
  longCallAlertEnabled?: boolean;
  remoteUnlockUntil?: number;
}

export interface SplitResult {
  /** What the helper may write directly: `wanted` with the risky parts kept as they are now. */
  direct: DeviceConfig;
  /** What has to wait for the parent, or null when nothing does. */
  request: RiskyChanges | null;
}

function familyNumbersKept(before: FamilyContact[], after: FamilyContact[]): boolean {
  return (
    before.length === after.length &&
    before.every((b, i) => {
      const a = after[i]!;
      return a.id === b.id && a.phone === b.phone && a.isPrimaryHelper === b.isPrimaryHelper;
    })
  );
}

/** Splits a config a helper wants to write into the part that applies now and the part to ask for. */
export function splitRiskyChanges(current: DeviceConfig, wanted: DeviceConfig): SplitResult {
  const direct: DeviceConfig = structuredClone(wanted);
  const request: RiskyChanges = {};
  const now = current.protection;
  const next = wanted.protection;

  if (!familyNumbersKept(current.family, wanted.family)) {
    request.family = wanted.family;
    direct.family = current.family;
  }

  const added = next.remoteAccessAlarm.trustedPackages.filter((p) => !now.remoteAccessAlarm.trustedPackages.includes(p));
  if (added.length > 0) {
    request.trustedPackages = next.remoteAccessAlarm.trustedPackages;
    direct.protection.remoteAccessAlarm.trustedPackages = now.remoteAccessAlarm.trustedPackages;
  }

  if (now.pauseTripwires.enabled && !next.pauseTripwires.enabled) {
    request.pauseTripwiresEnabled = false;
    direct.protection.pauseTripwires.enabled = true;
  }
  if (now.remoteAccessAlarm.enabled && !next.remoteAccessAlarm.enabled) {
    request.remoteAccessAlarmEnabled = false;
    direct.protection.remoteAccessAlarm.enabled = true;
  }
  if (now.longCallAlert.enabled && !next.longCallAlert.enabled) {
    request.longCallAlertEnabled = false;
    direct.protection.longCallAlert.enabled = true;
  }

  if (wanted.lock.remoteUnlockUntil > current.lock.remoteUnlockUntil) {
    request.remoteUnlockUntil = Math.round(wanted.lock.remoteUnlockUntil);
    direct.lock.remoteUnlockUntil = current.lock.remoteUnlockUntil;
  }

  return { direct, request: Object.keys(request).length > 0 ? request : null };
}

/** "turning off long-call notices and trusting a remote-control app", for "Mom will be asked about ...". */
export function describeRequest(request: RiskyChanges): string {
  const parts: string[] = [];
  if (request.family) parts.push('the family’s phone numbers');
  if (request.trustedPackages) parts.push('trusting a remote-control app');
  if (request.pauseTripwiresEnabled === false) parts.push('turning off the pause before sending money');
  if (request.remoteAccessAlarmEnabled === false) parts.push('turning off the remote-control app warning');
  if (request.longCallAlertEnabled === false) parts.push('turning off long-call notices');
  if (request.remoteUnlockUntil !== undefined) parts.push('unlocking the phone for a while');
  if (parts.length <= 1) return parts[0] ?? 'a protection setting';
  return `${parts.slice(0, -1).join(', ')} and ${parts.at(-1)!}`;
}
