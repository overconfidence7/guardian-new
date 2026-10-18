/*
 * The passive "I'm OK" check (SPEC F9). Pure: given what we know about a device and the
 * current instant, decide whether helpers should hear about it. All calendar maths happens
 * in the parent's time zone, so "today" and "11:00" mean what they mean to the parent,
 * including on daylight-saving change days.
 */
import { DateTime, IANAZone } from "luxon";
import { DEFAULT_CHECK_IN_DEADLINE, type CheckInSettings } from "./model.js";

export const SILENT_AFTER_MS = 24 * 60 * 60 * 1000;

export interface CheckInDevice {
  /** Null until a phone has claimed the device; unpaired devices are never checked. */
  deviceUid: string | null;
  checkIn: CheckInSettings | null;
  lastHeartbeatAt: Date | null;
  /** Parent-local date ("yyyy-MM-dd") we last sent the daily notice for. */
  checkInNotifiedFor: string | null;
  silentNotifiedAt: Date | null;
}

export type CheckInDecision =
  | { kind: "none"; reason: NoneReason }
  | { kind: "missed"; localDate: string; deadline: string }
  /**
   * The phone has been silent for over 24 hours. `markDay` is true when today's daily
   * notice is also due; the silent notice covers it so helpers get one message, not two.
   */
  | { kind: "silent"; localDate: string; markDay: boolean };

export type NoneReason =
  | "disabled"
  | "unpaired"
  | "invalid-timezone"
  | "before-deadline"
  | "used-today"
  | "already-notified";

const DEADLINE_RE = /^([01]\d|2[0-3]):([0-5]\d)$/;

export function parseDeadline(deadline: string): { hour: number; minute: number } {
  const match = DEADLINE_RE.exec(deadline.trim()) ?? DEADLINE_RE.exec(DEFAULT_CHECK_IN_DEADLINE)!;
  return { hour: Number(match[1]), minute: Number(match[2]) };
}

export function isValidTimeZone(tz: string): boolean {
  return typeof tz === "string" && tz.length > 0 && IANAZone.isValidZone(tz);
}

/**
 * The instant the deadline falls on for the given local date. On a spring-forward day a
 * deadline inside the skipped hour moves forward with the clocks (02:30 → 03:30), which is
 * what a person whose clock jumped would expect.
 */
export function deadlineInstant(localDay: DateTime, deadline: string): DateTime {
  const { hour, minute } = parseDeadline(deadline);
  return localDay.set({ hour, minute, second: 0, millisecond: 0 });
}

export function evaluateCheckIn(device: CheckInDevice, now: Date): CheckInDecision {
  const settings = device.checkIn;
  if (!settings?.enabled) return { kind: "none", reason: "disabled" };
  if (!device.deviceUid) return { kind: "none", reason: "unpaired" };
  if (!isValidTimeZone(settings.timezone)) return { kind: "none", reason: "invalid-timezone" };

  const localNow = DateTime.fromJSDate(now, { zone: settings.timezone });
  const localDate = localNow.toISODate()!;
  const startOfDay = localNow.startOf("day");
  const deadline = deadlineInstant(startOfDay, settings.deadline);
  const heartbeat = device.lastHeartbeatAt;

  const pastDeadline = localNow.toMillis() >= deadline.toMillis();
  const notUsedToday = heartbeat === null || heartbeat.getTime() < startOfDay.toMillis();
  const dailyDue = pastDeadline && notUsedToday && device.checkInNotifiedFor !== localDate;

  // One silent notice per silent stretch: a notice sent after the last heartbeat covers it.
  const silentDue =
    heartbeat !== null &&
    now.getTime() - heartbeat.getTime() > SILENT_AFTER_MS &&
    (device.silentNotifiedAt === null || device.silentNotifiedAt.getTime() < heartbeat.getTime());
  if (silentDue) {
    return { kind: "silent", localDate, markDay: dailyDue };
  }

  if (dailyDue) {
    const { hour, minute } = parseDeadline(settings.deadline);
    const configured = `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
    return { kind: "missed", localDate, deadline: configured };
  }
  if (!pastDeadline) return { kind: "none", reason: "before-deadline" };
  if (!notUsedToday) return { kind: "none", reason: "used-today" };
  return { kind: "none", reason: "already-notified" };
}
