/*
 * Risky config changes a helper can only ask for (docs/SECURITY.md §3). The dashboard
 * writes them to families/{f}/devices/{d}/requests/{id} as `changes`; the parent allows
 * or declines on the phone, which applies an allowed change to the config itself.
 * Keys are mirrored in firestore.rules (validChangeRequest), web/src/lib/riskyChanges.ts
 * and the phone's cloud/ChangeRequests.kt.
 */

export const RISKY_CHANGE_KEYS = [
  "family",
  "trustedPackages",
  "pauseTripwiresEnabled",
  "remoteAccessAlarmEnabled",
  "longCallAlertEnabled",
  "remoteUnlockUntil",
] as const;
export type RiskyChangeKey = (typeof RISKY_CHANGE_KEYS)[number];

const FEATURE_NAMES = {
  pauseTripwiresEnabled: "the pause before sending money",
  remoteAccessAlarmEnabled: "the remote-control app warning",
  longCallAlertEnabled: "long-call notices",
} as const;

/** One phrase per change, as "asked to ..." (ask) or as done ("David: ..."). */
function phrase(key: RiskyChangeKey, value: unknown, form: "ask" | "done"): string {
  const ask = form === "ask";
  switch (key) {
    case "family":
      return ask
        ? "change the family’s phone numbers or who gets called first"
        : "changed the family’s phone numbers or who gets called first";
    case "trustedPackages":
      return ask ? "trust a remote-control app" : "marked a remote-control app as trusted";
    case "remoteUnlockUntil":
      return ask ? "unlock the phone for a while" : "unlocked the phone for a while";
    default: {
      const on = value === true;
      const verb = ask ? (on ? "turn on" : "turn off") : on ? "turned on" : "turned off";
      return `${verb} ${FEATURE_NAMES[key]}`;
    }
  }
}

export function isRiskyChangeKey(value: string): value is RiskyChangeKey {
  return (RISKY_CHANGE_KEYS as readonly string[]).includes(value);
}

function joinPhrases(parts: string[]): string {
  if (parts.length <= 1) return parts[0] ?? "";
  return `${parts.slice(0, -1).join(", ")} and ${parts.at(-1)!}`;
}

function describe(changes: unknown, form: "ask" | "done"): string {
  const map = changes && typeof changes === "object" ? (changes as Record<string, unknown>) : {};
  const keys = RISKY_CHANGE_KEYS.filter((k) => k in map);
  if (keys.length === 0) return form === "ask" ? "change a protection setting" : "changed a protection setting";
  return joinPhrases(keys.map((k) => phrase(k, map[k], form)));
}

/** "turn off long-call notices and trust a remote-control app" (after "David asked to"). */
export function askPhrase(changes: unknown): string {
  return describe(changes, "ask");
}

/** "turned off long-call notices" (after "David:" on the phone's Recent changes). */
export function donePhrase(changes: unknown): string {
  return describe(changes, "done");
}
