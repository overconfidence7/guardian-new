/*
 * GM-COPY-002 (qa/antigravity/cases/18-wording.md): the helper-facing notification
 * wording in src/copy.ts reads as plain language, never leaks internal names, and says
 * what docs/SPEC.md's example copy says. GM-PRIV-024: no "monitor"-style words.
 */
import { describe, expect, it } from "vitest";
import { FEATURE_LABELS, PERMISSION_LABELS, notificationCopy } from "../../src/copy.js";
import { APP_CATEGORIES, EVENT_TYPES, SEVERITIES, type EventType, type Severity } from "../../src/lib/model.js";

/** Realistic data per type, as the phone (TripwireEngine, HelpActivity) and the server send it. */
function samples(type: EventType): Record<string, string>[] {
  const app = APP_CATEGORIES.map((category) => ({ app: "Chase", category }));
  switch (type) {
    case "PAUSE_SHOWN":
      return [
        ...app.map((a) => ({ ...a, duringCall: "false", minutesSinceCall: "4" })),
        { app: "Venmo", category: "PAYMENT", duringCall: "true", minutesSinceCall: "0" },
        {},
      ];
    case "PAUSE_CALLED_FAMILY":
    case "PAUSE_CONTINUED":
      return [{ app: "Chase" }, {}];
    case "REMOTE_ACCESS_INSTALLED":
    case "REMOTE_ACCESS_OPENED":
    case "CONTROL_APP_INSTALLED":
      return [{ app: "AnyDesk", afterUnknownCall: "true" }, { app: "anydesk", afterUnknownCall: "false" }, {}];
    case "LONG_UNKNOWN_CALL":
      return [{ minutes: "20", ongoing: "true" }, { minutes: "1", ongoing: "false" }, { minutes: "junk" }, {}];
    case "HELP_REQUESTED":
      return [{ note: "I'm stuck", path: "families/f/devices/d/help/abc.jpg" }, { path: "families/f/devices/d/help/abc.jpg" }, {}];
    case "REMINDER_MISSED":
      return [{ title: "Blood pressure pill", time: "08:00" }, { title: "Dentist" }, {}];
    case "PROTECTION_CHANGED_BY_PARENT":
      return [...Object.keys(FEATURE_LABELS), "unknownFeature"].flatMap((feature) => [
        { feature, enabled: "false" },
        { feature, enabled: "true" },
      ]);
    case "PERMISSION_MISSING":
      return [...Object.keys(PERMISSION_LABELS), "somethingNew"].map((permission) => ({ permission }));
    case "CHECK_IN_MISSED":
      return [{ deadline: "11:00", date: "2026-10-20" }, { reason: "silent" }, {}];
  }
}

const JARGON = /\b(overlay|role|tripwire\w*|severity|event|fcm|token|payload|package|enum|true|false|null|undefined|nan|json|uid|monitor\w*|track\w*|spy\w*|surveil\w*)\b/i;
const IDENTIFIER = /\b[a-z]+[A-Z]\w*\b|\b[A-Z]{2,}_[A-Z_]+\b|[{}<>_\\]|\b(BANK|PAYMENT|GIFT_CARD|CRYPTO|REMOTE_ACCESS)\b/;

describe("GM-COPY-002 helper-facing wording (functions/src/copy.ts)", () => {
  for (const type of EVENT_TYPES) {
    it(`GM-COPY-002 GM-PRIV-024 ${type}: plain words, no internal names, no path or number from the data`, () => {
      for (const severity of SEVERITIES) {
        for (const data of samples(type)) {
          const c = notificationCopy({ type, severity, data }, { parentName: "Mom" });
          const text = `${c.title}\n${c.body}`;
          const where = `${type}/${severity} ${JSON.stringify(data)}: ${text}`;
          expect(text, where).not.toMatch(JARGON);
          expect(text, where).not.toMatch(IDENTIFIER);
          expect(text, where).not.toContain("families/");
          expect(text, where).not.toMatch(/\d{3}/);
          expect(c.title, where).not.toMatch(/[.!]$/);
          expect(c.body, where).toMatch(/[.”]$/);
          expect(c.title + c.body, where).toContain("Mom");
          expect(c.body, where).not.toMatch(/ {2}|\s[.,]/);
        }
      }
    });
  }
});

describe("GM-COPY-002 the push wording says what docs/SPEC.md's examples say", () => {
  const copy = (type: EventType, severity: Severity, data: Record<string, string>) =>
    notificationCopy({ type, severity, data }, { parentName: "Mom" });
  const straight = (s: string) => s.replace(/[’]/g, "'");

  it("GM-COPY-002 F7 long call: \"Mom has been on a call with an unknown number for 20 minutes.\"", () => {
    expect(copy("LONG_UNKNOWN_CALL", "NOTICE", { minutes: "20", ongoing: "true" }).body).toBe(
      "Mom has been on a call with an unknown number for 20 minutes.",
    );
  });

  it("GM-COPY-002 F9 check-in: \"Mom's phone hasn't been used yet today.\"", () => {
    expect(`${straight(copy("CHECK_IN_MISSED", "NOTICE", { deadline: "11:00" }).title)}.`).toBe("Mom's phone hasn't been used yet today.");
  });

  // SPEC F4: Helpers get a notice ("Mom opened Chase 4 minutes after a call from an unknown
  // number. A pause card was shown."). The dashboard (web/src/lib/eventText.ts) uses that
  // sentence; the push body in copy.ts says "..., so the phone showed a pause card first."
  it("GM-COPY-002 F4 pause card: the push says \"Mom opened Chase 4 minutes after a call from an unknown number. A pause card was shown.\"", () => {
    expect(copy("PAUSE_SHOWN", "NOTICE", { app: "Chase", category: "BANK", duringCall: "false", minutesSinceCall: "4" }).body).toBe(
      "Mom opened Chase 4 minutes after a call from an unknown number. A pause card was shown.",
    );
  });
});
