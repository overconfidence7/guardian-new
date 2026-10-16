/*
 * Turns two DeviceConfig versions into plain sentences for the parent's "Recent changes"
 * list ("Changed text size", "Added Dr. Patel to contacts"). The safe word itself is
 * never repeated, only that it changed.
 */
import { friendlyTime } from "../copy.js";
import type { DeviceConfig, HomeTile, ScreeningMode } from "./model.js";

export interface DiffOptions {
  /** packageName → label, from the device's installedApps, for naming allowed apps. */
  appLabels?: Readonly<Record<string, string>>;
}

const TILE_KIND_NAMES: Record<HomeTile["kind"], string> = {
  CALL_CONTACT: "call",
  APP: "app",
  PHONE: "Phone",
  MESSAGES: "Messages",
  CAMERA: "Camera",
  PHOTOS: "Photos",
  REMINDERS: "Reminders",
  HELP: "Help",
  ALL_APPS: "All my apps",
};

const SCREENING_SENTENCES: Record<ScreeningMode, string> = {
  OFF: "Turned off unknown caller screening",
  SILENCE_UNKNOWN: "Set unknown callers to ring silently",
  VOICEMAIL_UNKNOWN: "Set unknown callers to go to voicemail",
};

function q(s: string): string {
  return `“${s}”`;
}

function onOff(enabled: boolean): string {
  return enabled ? "Turned on" : "Turned off";
}

function same(a: unknown, b: unknown): boolean {
  return stableJson(a) === stableJson(b);
}

function stableJson(value: unknown): string {
  return JSON.stringify(value, (_key, v: unknown) => {
    if (v && typeof v === "object" && !Array.isArray(v)) {
      return Object.fromEntries(Object.entries(v as Record<string, unknown>).sort(([a], [b]) => a.localeCompare(b)));
    }
    return v;
  });
}

function sameSet(a: readonly (string | number)[], b: readonly (string | number)[]): boolean {
  if (a.length !== b.length) return false;
  const sa = new Set(a);
  return b.every((x) => sa.has(x));
}

interface Keyed {
  id: string;
}

function diffById<T extends Keyed>(before: readonly T[], after: readonly T[]) {
  const beforeMap = new Map(before.map((x) => [x.id, x]));
  const afterMap = new Map(after.map((x) => [x.id, x]));
  const added = after.filter((x) => !beforeMap.has(x.id));
  const removed = before.filter((x) => !afterMap.has(x.id));
  const changed = after.filter((x) => {
    const old = beforeMap.get(x.id);
    return old !== undefined && !same(old, x);
  });
  const commonBefore = before.filter((x) => afterMap.has(x.id)).map((x) => x.id);
  const commonAfter = after.filter((x) => beforeMap.has(x.id)).map((x) => x.id);
  const reordered = commonBefore.join("\u0000") !== commonAfter.join("\u0000");
  return { added, removed, changed, reordered };
}

function tileName(tile: HomeTile): string {
  const label = tile.label.trim();
  if (label) return label;
  return TILE_KIND_NAMES[tile.kind];
}

function zoneName(tz: string): string {
  const last = tz.split("/").pop() ?? tz;
  return last.replace(/_/g, " ");
}

/** Human sentences for every change between two configs, in a stable order. */
export function describeConfigChanges(before: DeviceConfig, after: DeviceConfig, options: DiffOptions = {}): string[] {
  const out: string[] = [];
  const labelFor = (pkg: string): string => options.appLabels?.[pkg] ?? pkg;

  if (before.parentName.trim() !== after.parentName.trim() && after.parentName.trim()) {
    out.push(`Changed the name on the phone to ${after.parentName.trim()}`);
  }

  // Family
  const fam = diffById(before.family, after.family);
  for (const f of fam.added) out.push(`Added ${f.name} to family`);
  for (const f of fam.removed) out.push(`Removed ${f.name} from family`);
  const primaryBefore = before.family.find((f) => f.isPrimaryHelper)?.id;
  const primaryAfter = after.family.find((f) => f.isPrimaryHelper);
  for (const f of fam.changed) {
    const old = before.family.find((x) => x.id === f.id)!;
    const { isPrimaryHelper: _a, ...oldRest } = old;
    const { isPrimaryHelper: _b, ...newRest } = f;
    if (!same(oldRest, newRest)) out.push(`Updated ${f.name}’s details`);
  }
  if (primaryAfter && primaryAfter.id !== primaryBefore) {
    out.push(`Made ${primaryAfter.name} the first person to call`);
  }
  if (!fam.added.length && !fam.removed.length && fam.reordered) out.push("Reordered family");

  // Contacts
  const con = diffById(before.contacts, after.contacts);
  for (const c of con.added) out.push(`Added ${c.name} to contacts`);
  for (const c of con.removed) out.push(`Removed ${c.name} from contacts`);
  for (const c of con.changed) out.push(`Updated ${c.name} in contacts`);

  // Home screen
  const tiles = diffById(before.home.tiles, after.home.tiles);
  for (const t of tiles.added) out.push(`Added the ${tileName(t)} tile to the home screen`);
  for (const t of tiles.removed) out.push(`Removed the ${tileName(t)} tile from the home screen`);
  for (const t of tiles.changed) out.push(`Changed the ${tileName(t)} tile`);
  if (tiles.reordered) out.push("Rearranged the home screen");
  if (before.home.showPhotoCard !== after.home.showPhotoCard) {
    out.push(`${onOff(after.home.showPhotoCard)} the photo card`);
  }
  if (before.home.columns !== after.home.columns) {
    out.push(
      after.home.columns === 1
        ? "Changed the home screen to one column"
        : `Changed the home screen to ${after.home.columns} columns`,
    );
  }

  // Display
  if (before.display.fontScale !== after.display.fontScale) out.push("Changed text size");
  if (before.display.highContrast !== after.display.highContrast) {
    out.push(`${onOff(after.display.highContrast)} high contrast`);
  }
  if (before.display.theme !== after.display.theme) {
    const theme = { LIGHT: "light", DARK: "dark", SYSTEM: "match the phone" }[after.display.theme];
    out.push(after.display.theme === "SYSTEM" ? "Set the colors to match the phone" : `Changed the colors to ${theme}`);
  }

  // Allowed apps
  const appsAdded = after.allowedApps.filter((a) => !before.allowedApps.includes(a));
  const appsRemoved = before.allowedApps.filter((a) => !after.allowedApps.includes(a));
  for (const a of appsAdded) out.push(`Added ${labelFor(a)} to All my apps`);
  for (const a of appsRemoved) out.push(`Removed ${labelFor(a)} from All my apps`);

  // Protection
  const pb = before.protection;
  const pa = after.protection;
  if (pb.pauseTripwires.enabled !== pa.pauseTripwires.enabled) {
    out.push(`${onOff(pa.pauseTripwires.enabled)} the pause before sending money`);
  }
  if (pb.pauseTripwires.windowMinutes !== pa.pauseTripwires.windowMinutes) {
    out.push(`Changed the pause to last ${pa.pauseTripwires.windowMinutes} minutes after a call`);
  }
  if (
    !sameSet(pb.pauseTripwires.categories, pa.pauseTripwires.categories) ||
    !same(pb.pauseTripwires.extraPackages, pa.pauseTripwires.extraPackages)
  ) {
    const extrasBefore = pb.pauseTripwires.extraPackages;
    const extrasAfter = pa.pauseTripwires.extraPackages;
    const extraAdded = Object.keys(extrasAfter).filter((k) => !(k in extrasBefore));
    const onlyAdditions =
      sameSet(pb.pauseTripwires.categories, pa.pauseTripwires.categories) &&
      Object.entries(extrasBefore).every(([k, v]) => extrasAfter[k] === v);
    if (onlyAdditions) {
      for (const pkg of extraAdded) out.push(`Added ${labelFor(pkg)} to apps that get a pause card`);
    } else {
      out.push("Changed which apps get a pause card");
    }
  }

  const rb = pb.remoteAccessAlarm;
  const ra = pa.remoteAccessAlarm;
  if (rb.enabled !== ra.enabled) out.push(`${onOff(ra.enabled)} the remote-control app warning`);
  const trustedAdded = ra.trustedPackages.filter((p) => !rb.trustedPackages.includes(p));
  const trustedRemoved = rb.trustedPackages.filter((p) => !ra.trustedPackages.includes(p));
  for (const p of trustedAdded) out.push(`Marked ${labelFor(p)} as a trusted remote-control app`);
  for (const p of trustedRemoved) out.push(`Stopped trusting ${labelFor(p)} as a remote-control app`);
  if (rb.warnOnControlCapableInstalls !== ra.warnOnControlCapableInstalls) {
    out.push(`${onOff(ra.warnOnControlCapableInstalls)} warnings for new apps that can control the screen`);
  }

  const sb = pb.callScreening;
  const sa = pa.callScreening;
  if (sb.mode !== sa.mode) out.push(SCREENING_SENTENCES[sa.mode]);
  const allowBefore = new Map(sb.allowlist.map((a) => [a.number, a]));
  const allowAfter = new Map(sa.allowlist.map((a) => [a.number, a]));
  for (const [num, a] of allowAfter) {
    const old = allowBefore.get(num);
    if (!old) out.push(`Added ${a.label} to numbers that always ring`);
    else if (old.label !== a.label) out.push(`Renamed ${old.label} to ${a.label} in numbers that always ring`);
  }
  for (const [num, a] of allowBefore) {
    if (!allowAfter.has(num)) out.push(`Removed ${a.label} from numbers that always ring`);
  }
  if (sb.allowRepeatCallers !== sa.allowRepeatCallers) {
    out.push(
      sa.allowRepeatCallers
        ? "Let a second call from the same number ring through"
        : "Stopped letting a second call from the same number ring through",
    );
  }

  if (pb.longCallAlert.enabled !== pa.longCallAlert.enabled) {
    out.push(`${onOff(pa.longCallAlert.enabled)} long-call notices`);
  }
  if (pb.longCallAlert.minutes !== pa.longCallAlert.minutes) {
    out.push(`Changed long-call notices to ${pa.longCallAlert.minutes} minutes`);
  }

  if (pb.safeWord.enabled !== pa.safeWord.enabled) out.push(`${onOff(pa.safeWord.enabled)} the family safe word card`);
  if (pb.safeWord.word !== pa.safeWord.word) {
    out.push(pb.safeWord.word ? "Changed the family safe word" : "Set the family safe word");
  }

  // Check-in
  const cb = before.checkIn;
  const ca = after.checkIn;
  if (cb.enabled !== ca.enabled) out.push(`${onOff(ca.enabled)} the daily “I’m OK”`);
  if (cb.deadline !== ca.deadline) out.push(`Changed the daily “I’m OK” time to ${friendlyTime(ca.deadline)}`);
  if (cb.timezone !== ca.timezone) out.push(`Changed the time zone to ${zoneName(ca.timezone)}`);

  // Reminders
  const rem = diffById(before.reminders, after.reminders);
  for (const r of rem.added) out.push(`Added the reminder ${q(r.title)}`);
  for (const r of rem.removed) out.push(`Removed the reminder ${q(r.title)}`);
  for (const r of rem.changed) out.push(`Changed the reminder ${q(r.title)}`);

  return out;
}

function lowerFirst(s: string): string {
  return s.length === 0 ? s : s[0]!.toLowerCase() + s.slice(1);
}

/**
 * Joins sentences into one summary line: "Changed text size and added Dr. Patel to
 * contacts". Lists longer than `maxListed` end with "and N other changes" so the line stays readable.
 */
export function joinSummary(lines: readonly string[], maxListed = 3): string {
  if (lines.length === 0) return "";
  if (lines.length === 1) return lines[0]!;
  const head = lines[0]!;
  const rest = lines.slice(1).map(lowerFirst);
  if (lines.length <= maxListed) {
    if (rest.length === 1) return `${head} and ${rest[0]}`;
    return `${[head, ...rest.slice(0, -1)].join(", ")} and ${rest[rest.length - 1]}`;
  }
  // Name one fewer than the cap so the remainder is always "2 or more other changes".
  const shown = [head, ...rest.slice(0, Math.max(0, maxListed - 2))];
  return `${shown.join(", ")} and ${lines.length - shown.length} other changes`;
}

/** One-line summary, or null when nothing a person would notice changed. */
export function summarizeConfigChange(before: DeviceConfig, after: DeviceConfig, options: DiffOptions = {}): string | null {
  const lines = describeConfigChanges(before, after, options);
  return lines.length ? joinSummary(lines) : null;
}
