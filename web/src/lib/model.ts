/*
 * TypeScript mirror of core/src/main/kotlin/app/guardianmode/core/Model.kt.
 *
 * The JSON shape is the contract between the dashboard and the parent's phone. Every
 * field has the same default as the Kotlin model so either side can read documents the
 * other wrote with an older or newer schema. Parsing follows the phone's Json settings:
 * unknown keys are ignored and bad values fall back to defaults (coerceInputValues).
 */
import { z } from 'zod';

export const SCHEMA_VERSION = 1;

export const TILE_KINDS = [
  'CALL_CONTACT',
  'APP',
  'PHONE',
  'MESSAGES',
  'CAMERA',
  'PHOTOS',
  'REMINDERS',
  'HELP',
  'ALL_APPS',
] as const;
export type TileKind = (typeof TILE_KINDS)[number];

export const THEME_PREFERENCES = ['LIGHT', 'DARK', 'SYSTEM'] as const;
export type ThemePreference = (typeof THEME_PREFERENCES)[number];

export const SCREENING_MODES = ['OFF', 'SILENCE_UNKNOWN', 'VOICEMAIL_UNKNOWN'] as const;
export type ScreeningMode = (typeof SCREENING_MODES)[number];

export const REMINDER_KINDS = ['MEDICATION', 'APPOINTMENT', 'OTHER'] as const;
export type ReminderKind = (typeof REMINDER_KINDS)[number];

export const APP_CATEGORIES = ['PAYMENT', 'GIFT_CARD', 'CRYPTO', 'BANK', 'REMOTE_ACCESS'] as const;
export type AppCategory = (typeof APP_CATEGORIES)[number];

export const EVENT_TYPES = [
  'LONG_UNKNOWN_CALL',
  'PAUSE_SHOWN',
  'PAUSE_CALLED_FAMILY',
  'PAUSE_CONTINUED',
  'REMOTE_ACCESS_INSTALLED',
  'REMOTE_ACCESS_OPENED',
  'CONTROL_APP_INSTALLED',
  'HELP_REQUESTED',
  'REMINDER_MISSED',
  'PROTECTION_CHANGED_BY_PARENT',
  'PERMISSION_MISSING',
  'CHECK_IN_MISSED',
] as const;
export type EventType = (typeof EVENT_TYPES)[number];

/**
 * Family notices only the server writes (firebase/functions/src/lib/model.ts
 * SERVER_EVENT_TYPES): a helper added or removed, a risky change answered on the phone.
 * Not in Model.kt; the phone never sends them.
 */
export const SERVER_EVENT_TYPES = ['HELPER_CHANGED', 'CHANGE_REQUEST_ANSWERED', 'PHONE_LEFT'] as const;
export type ServerEventType = (typeof SERVER_EVENT_TYPES)[number];
export type AnyEventType = EventType | ServerEventType;

export const SEVERITIES = ['INFO', 'NOTICE', 'URGENT'] as const;
export type Severity = (typeof SEVERITIES)[number];

export interface FamilyContact {
  id: string;
  name: string;
  relation: string;
  phone: string;
  photoUrl?: string | null;
  isPrimaryHelper: boolean;
}

export interface Contact {
  id: string;
  name: string;
  phone: string;
  photoUrl?: string | null;
  note: string;
}

export interface HomeTile {
  id: string;
  kind: TileKind;
  /** Contact id for CALL_CONTACT, package name for APP, unused otherwise. */
  target: string;
  label: string;
}

export interface HomeLayout {
  tiles: HomeTile[];
  showPhotoCard: boolean;
  columns: number;
}

export interface DisplaySettings {
  /** Multiplier on the base type scale. 1.0 is already larger than stock Android. */
  fontScale: number;
  highContrast: boolean;
  theme: ThemePreference;
}

export interface PauseSettings {
  enabled: boolean;
  /** How long after an unknown call ends the tripwires stay armed. */
  windowMinutes: number;
  /** A set on the phone; kept unique here. */
  categories: AppCategory[];
  /** Family-added packages, e.g. the parent's local credit-union app. */
  extraPackages: Record<string, AppCategory>;
}

export interface RemoteAccessSettings {
  enabled: boolean;
  /** Remote-access apps the family itself uses to help; these never alarm. */
  trustedPackages: string[];
  /** Also warn on any newly installed app that can control the screen. */
  warnOnControlCapableInstalls: boolean;
}

export interface AllowedNumber {
  label: string;
  number: string;
}

export interface ScreeningSettings {
  mode: ScreeningMode;
  allowlist: AllowedNumber[];
  /** A second call from the same unknown number within a few minutes rings normally. */
  allowRepeatCallers: boolean;
}

export interface LongCallSettings {
  enabled: boolean;
  minutes: number;
}

export interface SafeWordSettings {
  enabled: boolean;
  /** Stays on the parent's phone so they can look it up; never sent back in events. */
  word: string;
}

export interface ProtectionSettings {
  pauseTripwires: PauseSettings;
  remoteAccessAlarm: RemoteAccessSettings;
  callScreening: ScreeningSettings;
  longCallAlert: LongCallSettings;
  safeWord: SafeWordSettings;
}

export interface CheckInSettings {
  enabled: boolean;
  /** Local time ("HH:mm") by which the phone should have been used today. */
  deadline: string;
  timezone: string;
}

export interface Reminder {
  id: string;
  title: string;
  detail: string;
  kind: ReminderKind;
  /** Local times "HH:mm". */
  times: string[];
  /** ISO day numbers 1 (Monday) to 7 (Sunday). Empty means every day. */
  daysOfWeek: number[];
  /** For one-off appointments: ISO date "yyyy-MM-dd". */
  date?: string | null;
  /** Tell the family if a medication reminder is not confirmed within an hour. */
  notifyFamilyIfMissed: boolean;
}

export interface DeviceConfig {
  schemaVersion: number;
  revision: number;
  parentName: string;
  family: FamilyContact[];
  contacts: Contact[];
  home: HomeLayout;
  display: DisplaySettings;
  allowedApps: string[];
  protection: ProtectionSettings;
  checkIn: CheckInSettings;
  reminders: Reminder[];
  lock: LockSettings;
}

export type LockLevel = 'SIMPLE' | 'GUIDED' | 'LOCKED';

export interface LockSettings {
  level: LockLevel;
  /** A helper's temporary unlock, epoch millis. */
  remoteUnlockUntil: number;
}

export interface GuardianEvent {
  id: string;
  type: EventType;
  at: number;
  severity: Severity;
  data: Record<string, string>;
}

export const DEFAULT_PAUSE_CATEGORIES: AppCategory[] = ['PAYMENT', 'GIFT_CARD', 'CRYPTO', 'BANK'];

/** Exactly the Kotlin defaults: `DeviceConfig()`. */
export function defaultDeviceConfig(overrides: Partial<Pick<DeviceConfig, 'parentName'>> = {}): DeviceConfig {
  return {
    schemaVersion: SCHEMA_VERSION,
    revision: 0,
    parentName: overrides.parentName ?? '',
    family: [],
    contacts: [],
    home: defaultHomeLayout(),
    display: defaultDisplaySettings(),
    allowedApps: [],
    protection: defaultProtectionSettings(),
    checkIn: defaultCheckInSettings(),
    reminders: [],
    lock: defaultLockSettings(),
  };
}

export const defaultLockSettings = (): LockSettings => ({ level: 'SIMPLE', remoteUnlockUntil: 0 });

export const defaultHomeLayout = (): HomeLayout => ({ tiles: [], showPhotoCard: true, columns: 2 });
export const defaultDisplaySettings = (): DisplaySettings => ({ fontScale: 1.3, highContrast: false, theme: 'LIGHT' });
export const defaultPauseSettings = (): PauseSettings => ({
  enabled: true,
  windowMinutes: 30,
  categories: [...DEFAULT_PAUSE_CATEGORIES],
  extraPackages: {},
});
export const defaultRemoteAccessSettings = (): RemoteAccessSettings => ({
  enabled: true,
  trustedPackages: [],
  warnOnControlCapableInstalls: true,
});
export const defaultScreeningSettings = (): ScreeningSettings => ({
  mode: 'SILENCE_UNKNOWN',
  allowlist: [],
  allowRepeatCallers: true,
});
export const defaultLongCallSettings = (): LongCallSettings => ({ enabled: true, minutes: 20 });
export const defaultSafeWordSettings = (): SafeWordSettings => ({ enabled: true, word: '' });
export const defaultProtectionSettings = (): ProtectionSettings => ({
  pauseTripwires: defaultPauseSettings(),
  remoteAccessAlarm: defaultRemoteAccessSettings(),
  callScreening: defaultScreeningSettings(),
  longCallAlert: defaultLongCallSettings(),
  safeWord: defaultSafeWordSettings(),
});
export const defaultCheckInSettings = (): CheckInSettings => ({
  enabled: true,
  deadline: '11:00',
  timezone: 'America/New_York',
});

/** Mirrors `DeviceConfig.primaryHelper` in Kotlin. */
export function primaryHelper(config: Pick<DeviceConfig, 'family'>): FamilyContact | undefined {
  return (
    config.family.find((f) => f.isPrimaryHelper && f.phone.trim() !== '') ??
    config.family.find((f) => f.phone.trim() !== '')
  );
}

/* ------------------------------------------------------------------------------------
 * Schemas. `lenient` mirrors the phone's decoder (defaults + coercion); `strict` is for
 * contract tests: right types, valid enums, no unknown keys.
 * ---------------------------------------------------------------------------------- */

type Mode = 'lenient' | 'strict';

function buildSchemas(mode: Mode) {
  const lenient = mode === 'lenient';
  const obj = <T extends z.ZodRawShape>(shape: T) => (lenient ? z.object(shape) : z.strictObject(shape));
  // A field with a default: lenient falls back on missing or invalid; strict allows missing only.
  const d = <S extends z.ZodType>(schema: S, fallback: z.output<S>) =>
    lenient ? schema.catch(() => structuredClone(fallback)) : schema.optional();
  const uniq = <T>(arr: T[]) => Array.from(new Set(arr));

  const hhmm = z.string().regex(/^([01]\d|2[0-3]):[0-5]\d$/);
  const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);

  const tileKind = z.enum(TILE_KINDS);
  const appCategory = z.enum(APP_CATEGORIES);

  // Lists drop malformed items rather than failing the whole document.
  const list = <S extends z.ZodType>(item: S) =>
    lenient
      ? z
          .array(z.unknown())
          .catch([])
          .transform((items) =>
            items.flatMap((it) => {
              const r = item.safeParse(it);
              return r.success ? [r.data as z.output<S>] : [];
            }),
          )
      : z.array(item);

  const familyContact = obj({
    id: z.string(),
    name: z.string(),
    relation: d(z.string(), ''),
    phone: d(z.string(), ''),
    photoUrl: z.string().nullable().optional(),
    isPrimaryHelper: d(z.boolean(), false),
  });

  const contact = obj({
    id: z.string(),
    name: z.string(),
    phone: z.string(),
    photoUrl: z.string().nullable().optional(),
    note: d(z.string(), ''),
  });

  const homeTile = obj({
    id: z.string(),
    kind: tileKind,
    target: d(z.string(), ''),
    label: d(z.string(), ''),
  });

  const home = obj({
    tiles: d(list(homeTile), []),
    showPhotoCard: d(z.boolean(), true),
    columns: d(z.number().int().min(1).max(4), 2),
  });

  const display = obj({
    fontScale: d(z.number().min(0.5).max(3), 1.3),
    highContrast: d(z.boolean(), false),
    theme: d(z.enum(THEME_PREFERENCES), 'LIGHT'),
  });

  const pause = obj({
    enabled: d(z.boolean(), true),
    windowMinutes: d(z.number().int().min(0), 30),
    categories: d(
      lenient ? list(appCategory).transform(uniq) : z.array(appCategory),
      [...DEFAULT_PAUSE_CATEGORIES],
    ),
    extraPackages: d(
      lenient
        ? z
            .record(z.string(), z.unknown())
            .catch({})
            .transform((rec) =>
              Object.fromEntries(
                Object.entries(rec).filter((e): e is [string, AppCategory] => appCategory.safeParse(e[1]).success),
              ),
            )
        : z.record(z.string(), appCategory),
      {},
    ),
  });

  const remote = obj({
    enabled: d(z.boolean(), true),
    trustedPackages: d(lenient ? list(z.string()).transform(uniq) : z.array(z.string()), []),
    warnOnControlCapableInstalls: d(z.boolean(), true),
  });

  const allowedNumber = obj({ label: z.string(), number: z.string() });
  const screening = obj({
    mode: d(z.enum(SCREENING_MODES), 'SILENCE_UNKNOWN'),
    allowlist: d(list(allowedNumber), []),
    allowRepeatCallers: d(z.boolean(), true),
  });

  const longCall = obj({ enabled: d(z.boolean(), true), minutes: d(z.number().int().min(1), 20) });
  const safeWord = obj({ enabled: d(z.boolean(), true), word: d(z.string(), '') });

  const protection = obj({
    pauseTripwires: d(pause, defaultPauseSettings()),
    remoteAccessAlarm: d(remote, defaultRemoteAccessSettings()),
    callScreening: d(screening, defaultScreeningSettings()),
    longCallAlert: d(longCall, defaultLongCallSettings()),
    safeWord: d(safeWord, defaultSafeWordSettings()),
  });

  const checkIn = obj({
    enabled: d(z.boolean(), true),
    deadline: d(hhmm, '11:00'),
    timezone: d(z.string().min(1), 'America/New_York'),
  });

  const reminder = obj({
    id: z.string(),
    title: z.string(),
    detail: d(z.string(), ''),
    kind: d(z.enum(REMINDER_KINDS), 'MEDICATION'),
    times: d(lenient ? list(hhmm) : z.array(hhmm), []),
    daysOfWeek: d(
      lenient ? list(z.number().int().min(1).max(7)).transform(uniq) : z.array(z.number().int().min(1).max(7)),
      [],
    ),
    date: isoDate.nullable().optional(),
    notifyFamilyIfMissed: d(z.boolean(), false),
  });

  const config = obj({
    schemaVersion: d(z.number().int(), SCHEMA_VERSION),
    revision: d(z.number(), 0),
    parentName: d(z.string(), ''),
    family: d(list(familyContact), []),
    contacts: d(list(contact), []),
    home: d(home, defaultHomeLayout()),
    display: d(display, defaultDisplaySettings()),
    allowedApps: d(lenient ? list(z.string()) : z.array(z.string()), []),
    protection: d(protection, defaultProtectionSettings()),
    checkIn: d(checkIn, defaultCheckInSettings()),
    reminders: d(list(reminder), []),
    lock: d(obj({ level: d(z.enum(['SIMPLE', 'GUIDED', 'LOCKED']), 'SIMPLE'), remoteUnlockUntil: d(z.number(), 0) }), defaultLockSettings()),
  });

  const event = obj({
    id: z.string(),
    type: z.enum(EVENT_TYPES),
    at: z.number(),
    severity: z.enum(SEVERITIES),
    data: d(z.record(z.string(), z.string()), {}),
  });

  return { config, event, reminder, homeTile, familyContact, contact };
}

export const LenientSchemas = buildSchemas('lenient');
export const StrictSchemas = buildSchemas('strict');

/** Decode a config document the way the phone does. Never throws for object input. */
export function parseDeviceConfig(raw: unknown): DeviceConfig {
  const result = LenientSchemas.config.safeParse(raw ?? {});
  return result.success ? (result.data as DeviceConfig) : defaultDeviceConfig();
}

/** Validate a document strictly (contract tests, dev tooling). */
export function validateDeviceConfigStrict(raw: unknown) {
  return StrictSchemas.config.safeParse(raw);
}
