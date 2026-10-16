import type { DeviceConfig } from "./model.js";

/**
 * Features the parent can switch off from the phone, keyed like core/Transparency.kt so
 * the copy and the transparency screen talk about the same things.
 */
const FEATURES: Record<string, (c: DeviceConfig) => boolean> = {
  pause: (c) => c.protection.pauseTripwires.enabled,
  remoteAccess: (c) => c.protection.remoteAccessAlarm.enabled,
  screening: (c) => c.protection.callScreening.mode !== "OFF",
  longCall: (c) => c.protection.longCallAlert.enabled,
  safeWord: (c) => c.protection.safeWord.enabled,
  checkIn: (c) => c.checkIn.enabled,
};

/** Feature keys that went from on to off between two configs. */
export function featuresTurnedOff(before: DeviceConfig, after: DeviceConfig): string[] {
  return Object.entries(FEATURES)
    .filter(([, isOn]) => isOn(before) && !isOn(after))
    .map(([key]) => key);
}
