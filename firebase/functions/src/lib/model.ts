/*
 * TypeScript mirror of core/src/main/kotlin/app/guardianmode/core/Model.kt.
 *
 * The schemas are lenient in the same way the phone's JSON decoder is (missing fields
 * take their defaults, unknown fields are ignored, unknown enum values fall back to the
 * default) so that the server never chokes on a config written by a newer or older client.
 */
import { z } from "zod";

export const SCHEMA_VERSION = 1;

export const EVENT_TYPES = [
  "LONG_UNKNOWN_CALL",
  "PAUSE_SHOWN",
  "PAUSE_CALLED_FAMILY",
  "PAUSE_CONTINUED",
  "REMOTE_ACCESS_INSTALLED",
  "REMOTE_ACCESS_OPENED",
  "CONTROL_APP_INSTALLED",
  "HELP_REQUESTED",
  "REMINDER_MISSED",
  "PROTECTION_CHANGED_BY_PARENT",
  "PERMISSION_MISSING",
  "CHECK_IN_MISSED",
] as const;
export type EventType = (typeof EVENT_TYPES)[number];

/**
 * Notices only the server writes, about the family itself rather than the phone
 * (docs/SECURITY.md §3 and §6). The phone never sends or reads these, so they are not in
 * Model.kt's EventType or the rules' allowlist.
 *   HELPER_CHANGED           a helper was added, declined, removed or left
 *   CHANGE_REQUEST_ANSWERED  the parent allowed or declined a helper's risky change
 *   PHONE_LEFT               a phone left the family (pushed directly; its events go with it)
 */
export const SERVER_EVENT_TYPES = ["HELPER_CHANGED", "CHANGE_REQUEST_ANSWERED", "PHONE_LEFT"] as const;
export type ServerEventType = (typeof SERVER_EVENT_TYPES)[number];
export type AnyEventType = EventType | ServerEventType;

export const SEVERITIES = ["INFO", "NOTICE", "URGENT"] as const;
export type Severity = (typeof SEVERITIES)[number];

export const TILE_KINDS = [
  "CALL_CONTACT",
  "APP",
  "PHONE",
  "MESSAGES",
  "CAMERA",
  "PHOTOS",
  "REMINDERS",
  "HELP",
  "ALL_APPS",
] as const;
export type TileKind = (typeof TILE_KINDS)[number];

export const THEMES = ["LIGHT", "DARK", "SYSTEM"] as const;
export const SCREENING_MODES = ["OFF", "SILENCE_UNKNOWN", "VOICEMAIL_UNKNOWN"] as const;
export type ScreeningMode = (typeof SCREENING_MODES)[number];
export const REMINDER_KINDS = ["MEDICATION", "APPOINTMENT", "OTHER"] as const;
export const APP_CATEGORIES = ["PAYMENT", "GIFT_CARD", "CRYPTO", "BANK", "REMOTE_ACCESS"] as const;
export type AppCategory = (typeof APP_CATEGORIES)[number];

export const DEFAULT_CHECK_IN_DEADLINE = "11:00";
export const DEFAULT_TIMEZONE = "America/New_York";

const familyContactSchema = z.object({
  id: z.string(),
  name: z.string(),
  relation: z.string().default(""),
  phone: z.string().default(""),
  photoUrl: z.string().nullable().default(null),
  isPrimaryHelper: z.boolean().default(false),
});

const contactSchema = z.object({
  id: z.string(),
  name: z.string(),
  phone: z.string(),
  photoUrl: z.string().nullable().default(null),
  note: z.string().default(""),
});

const homeTileSchema = z.object({
  id: z.string(),
  kind: z.enum(TILE_KINDS),
  target: z.string().default(""),
  label: z.string().default(""),
});

const homeLayoutSchema = z.object({
  tiles: z.array(homeTileSchema).default([]),
  showPhotoCard: z.boolean().default(true),
  columns: z.number().int().default(2),
});

const displaySchema = z.object({
  fontScale: z.number().default(1.3),
  highContrast: z.boolean().default(false),
  theme: z.enum(THEMES).catch("LIGHT").default("LIGHT"),
});

const pauseSchema = z.object({
  enabled: z.boolean().default(true),
  windowMinutes: z.number().int().default(30),
  categories: z.array(z.enum(APP_CATEGORIES)).default(["PAYMENT", "GIFT_CARD", "CRYPTO", "BANK"]),
  extraPackages: z.record(z.string(), z.enum(APP_CATEGORIES)).default({}),
});

const remoteAccessSchema = z.object({
  enabled: z.boolean().default(true),
  trustedPackages: z.array(z.string()).default([]),
  warnOnControlCapableInstalls: z.boolean().default(true),
});

const allowedNumberSchema = z.object({ label: z.string(), number: z.string() });

const screeningSchema = z.object({
  mode: z.enum(SCREENING_MODES).catch("SILENCE_UNKNOWN").default("SILENCE_UNKNOWN"),
  allowlist: z.array(allowedNumberSchema).default([]),
  allowRepeatCallers: z.boolean().default(true),
});

const longCallSchema = z.object({
  enabled: z.boolean().default(true),
  minutes: z.number().int().default(20),
});

const safeWordSchema = z.object({
  enabled: z.boolean().default(true),
  word: z.string().default(""),
});

const protectionSchema = z.object({
  pauseTripwires: pauseSchema.prefault({}),
  remoteAccessAlarm: remoteAccessSchema.prefault({}),
  callScreening: screeningSchema.prefault({}),
  longCallAlert: longCallSchema.prefault({}),
  safeWord: safeWordSchema.prefault({}),
});

export const checkInSchema = z.object({
  enabled: z.boolean().default(true),
  deadline: z.string().default(DEFAULT_CHECK_IN_DEADLINE),
  timezone: z.string().default(DEFAULT_TIMEZONE),
});

const reminderSchema = z.object({
  id: z.string(),
  title: z.string(),
  detail: z.string().default(""),
  kind: z.enum(REMINDER_KINDS).catch("MEDICATION").default("MEDICATION"),
  times: z.array(z.string()).default([]),
  daysOfWeek: z.array(z.number().int().min(1).max(7)).default([]),
  date: z.string().nullable().default(null),
  notifyFamilyIfMissed: z.boolean().default(false),
});

export const deviceConfigSchema = z.object({
  schemaVersion: z.number().int().default(SCHEMA_VERSION),
  revision: z.number().int().default(0),
  parentName: z.string().default(""),
  family: z.array(familyContactSchema).default([]),
  contacts: z.array(contactSchema).default([]),
  home: homeLayoutSchema.prefault({}),
  display: displaySchema.prefault({}),
  allowedApps: z.array(z.string()).default([]),
  protection: protectionSchema.prefault({}),
  checkIn: checkInSchema.prefault({}),
  reminders: z.array(reminderSchema).default([]),
  lock: z
    .object({
      level: z.enum(["SIMPLE", "GUIDED", "LOCKED"]).default("SIMPLE"),
      remoteUnlockUntil: z.number().default(0),
    })
    .prefault({}),
});

export type DeviceConfig = z.infer<typeof deviceConfigSchema>;
export type FamilyContact = DeviceConfig["family"][number];
export type Contact = DeviceConfig["contacts"][number];
export type HomeTile = DeviceConfig["home"]["tiles"][number];
export type Reminder = DeviceConfig["reminders"][number];
export type CheckInSettings = DeviceConfig["checkIn"];

/** Strict parse: throws on malformed input. Used for tests and for configs the server writes. */
export function parseDeviceConfig(raw: unknown): DeviceConfig {
  return deviceConfigSchema.parse(raw);
}

/**
 * Best-effort parse of whatever a client stored. Returns null when the document is not
 * a usable config at all (for example a list item without an id), so callers can fall
 * back to generic wording instead of failing.
 */
export function readDeviceConfig(raw: unknown): DeviceConfig | null {
  const result = deviceConfigSchema.safeParse(raw ?? {});
  return result.success ? result.data : null;
}

export function readCheckIn(raw: unknown): CheckInSettings {
  const result = checkInSchema.safeParse(raw ?? {});
  return result.success ? result.data : checkInSchema.parse({});
}

export function isEventType(value: unknown): value is EventType {
  return typeof value === "string" && (EVENT_TYPES as readonly string[]).includes(value);
}

/** Any type the server knows how to word and deliver: the phone's and its own. */
export function isDeliverableEventType(value: unknown): value is AnyEventType {
  return isEventType(value) || (typeof value === "string" && (SERVER_EVENT_TYPES as readonly string[]).includes(value));
}

export function isSeverity(value: unknown): value is Severity {
  return typeof value === "string" && (SEVERITIES as readonly string[]).includes(value);
}

export type MemberRole = "owner" | "helper";
export type MemberStatus = "pending" | "active";
export type NotifyChoice = "push" | "off";

export interface NotifyPrefs {
  urgent: "push";
  notice: NotifyChoice;
}

export const DEFAULT_NOTIFY: NotifyPrefs = { urgent: "push", notice: "push" };

/** Invite codes: 6 characters, no look-alikes (0/O, 1/I/L). */
export const INVITE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
export const INVITE_CODE_LENGTH = 6;
export const DEVICE_INVITE_TTL_MS = 15 * 60 * 1000;
export const HELPER_INVITE_TTL_MS = 7 * 24 * 60 * 60 * 1000;

export type Plan = "free" | "family";

export const PLAN_LIMITS: Record<Plan, { devices: number; helpers: number }> = {
  free: { devices: 1, helpers: 1 },
  family: { devices: 2, helpers: 4 },
};

export function planLimits(plan: unknown): { devices: number; helpers: number } {
  return plan === "family" ? PLAN_LIMITS.family : PLAN_LIMITS.free;
}

/** Event data bounds, mirrored in firestore.rules. */
export const MAX_EVENT_DATA_KEYS = 10;
export const MAX_EVENT_DATA_VALUE_LENGTH = 200;

/** Events are kept for 90 days (TTL policy on `expireAt`). */
export const EVENT_RETENTION_MS = 90 * 24 * 60 * 60 * 1000;
