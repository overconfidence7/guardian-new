import {
  DEFAULT_CHECK_IN_DEADLINE,
  DEFAULT_TIMEZONE,
  SCHEMA_VERSION,
  type DeviceConfig,
  type FamilyContact,
  type HomeTile,
} from "./model.js";

export interface DefaultConfigInput {
  parentName: string;
  /** The helper creating the family; added as primary helper when they gave a phone number. */
  helper?: { uid: string; name: string; phone?: string | null };
  timezone?: string;
}

/** Stable FamilyContact id for a helper, so later edits and removals can find their entry. */
export function familyContactIdFor(uid: string): string {
  return `fam_${uid}`;
}

export function helperFamilyContact(
  uid: string,
  name: string,
  phone: string,
  isPrimaryHelper: boolean,
): FamilyContact {
  return {
    id: familyContactIdFor(uid),
    name,
    relation: "",
    phone,
    photoUrl: null,
    isPrimaryHelper,
  };
}

/**
 * The configuration a new phone starts with. Mirrors the Kotlin defaults in Model.kt and
 * adds a starter home screen so the phone is useful the moment it is paired.
 */
export function defaultDeviceConfig(input: DefaultConfigInput): DeviceConfig {
  const phone = input.helper?.phone?.trim() ?? "";
  const family: FamilyContact[] =
    input.helper && phone ? [helperFamilyContact(input.helper.uid, input.helper.name, phone, true)] : [];

  const tiles: HomeTile[] = [];
  const primary = family[0];
  if (primary) {
    tiles.push({ id: "t_call_helper", kind: "CALL_CONTACT", target: primary.id, label: primary.name });
  }
  tiles.push(
    { id: "t_phone", kind: "PHONE", target: "", label: "Phone" },
    { id: "t_messages", kind: "MESSAGES", target: "", label: "Messages" },
    { id: "t_camera", kind: "CAMERA", target: "", label: "Camera" },
    { id: "t_photos", kind: "PHOTOS", target: "", label: "Photos" },
    { id: "t_reminders", kind: "REMINDERS", target: "", label: "Reminders" },
    { id: "t_help", kind: "HELP", target: "", label: "Help" },
  );

  return {
    schemaVersion: SCHEMA_VERSION,
    revision: 0,
    parentName: input.parentName,
    family,
    contacts: [],
    home: { tiles, showPhotoCard: true, columns: 2 },
    display: { fontScale: 1.3, highContrast: false, theme: "LIGHT" },
    allowedApps: [],
    protection: {
      pauseTripwires: {
        enabled: true,
        windowMinutes: 30,
        categories: ["PAYMENT", "GIFT_CARD", "CRYPTO", "BANK"],
        extraPackages: {},
      },
      remoteAccessAlarm: { enabled: true, trustedPackages: [], warnOnControlCapableInstalls: true },
      callScreening: { mode: "SILENCE_UNKNOWN", allowlist: [], allowRepeatCallers: true },
      longCallAlert: { enabled: true, minutes: 20 },
      safeWord: { enabled: true, word: "" },
    },
    checkIn: {
      enabled: true,
      deadline: DEFAULT_CHECK_IN_DEADLINE,
      timezone: input.timezone ?? DEFAULT_TIMEZONE,
    },
    reminders: [],
    lock: { level: "SIMPLE", remoteUnlockUntil: 0 },
  };
}
