import { describe, expect, it } from "vitest";
import { friendlyTime, notificationCopy, type CopyEvent } from "../../src/copy.js";
import { EVENT_TYPES, SEVERITIES, type EventType, type Severity } from "../../src/lib/model.js";

const ctx = { parentName: "Margaret" };

function copy(type: EventType, data: Record<string, string> = {}, severity: Severity = "NOTICE") {
  const event: CopyEvent = { type, severity, data };
  return notificationCopy(event, ctx);
}

describe("notificationCopy", () => {
  it("LONG_UNKNOWN_CALL, ongoing", () => {
    expect(copy("LONG_UNKNOWN_CALL", { minutes: "20", ongoing: "true" })).toEqual({
      title: "Margaret is on a long call",
      body: "Margaret has been on a call with an unknown number for 20 minutes.",
    });
  });

  it("LONG_UNKNOWN_CALL, ended, urgent mentions the app", () => {
    const c = copy("LONG_UNKNOWN_CALL", { minutes: "34", ongoing: "false" }, "URGENT");
    expect(c.title).toBe("Margaret had a long call");
    expect(c.body).toBe(
      "Margaret was on a call with an unknown number for 34 minutes. A money or remote-control app was opened during the call.",
    );
  });

  it("PAUSE_SHOWN after a call", () => {
    expect(copy("PAUSE_SHOWN", { app: "Chase", duringCall: "false", minutesSinceCall: "4" })).toEqual({
      title: "Margaret paused before opening Chase",
      body: "Margaret opened Chase 4 minutes after a call from an unknown number, so the phone showed a pause card first.",
    });
  });

  it("PAUSE_SHOWN singular minute and zero minutes", () => {
    expect(copy("PAUSE_SHOWN", { app: "Venmo", minutesSinceCall: "1" }).body).toContain("Venmo 1 minute after");
    expect(copy("PAUSE_SHOWN", { app: "Venmo", minutesSinceCall: "0" }).body).toContain(
      "Venmo just after a call from an unknown number",
    );
  });

  it("PAUSE_SHOWN during a call", () => {
    expect(copy("PAUSE_SHOWN", { app: "Chase", duringCall: "true" }, "URGENT").body).toBe(
      "Margaret opened Chase during a call with an unknown number, so the phone showed a pause card first.",
    );
  });

  it("PAUSE_CALLED_FAMILY and PAUSE_CONTINUED", () => {
    expect(copy("PAUSE_CALLED_FAMILY", { app: "Chase" }).title).toBe("Margaret chose to call family first");
    expect(copy("PAUSE_CONTINUED", { app: "Chase" })).toEqual({
      title: "Margaret went ahead with Chase",
      body: "Margaret saw the pause card for Chase and went ahead anyway. A quick call will tell you if everything’s all right.",
    });
  });

  it("REMOTE_ACCESS_INSTALLED after an unknown call", () => {
    expect(copy("REMOTE_ACCESS_INSTALLED", { app: "AnyDesk", afterUnknownCall: "true" }, "URGENT")).toEqual({
      title: "AnyDesk was installed on Margaret’s phone",
      body: "AnyDesk lets someone control the phone from somewhere else. It was installed during or soon after a call with an unknown number. Margaret saw a warning with your number on it. A quick call will tell you.",
    });
  });

  it("REMOTE_ACCESS_INSTALLED without a call", () => {
    expect(copy("REMOTE_ACCESS_INSTALLED", { app: "AnyDesk", afterUnknownCall: "false" }).body).toBe(
      "AnyDesk lets someone control the phone from somewhere else. Margaret saw a warning with your number on it. A quick call will tell you.",
    );
  });

  it("REMOTE_ACCESS_OPENED and CONTROL_APP_INSTALLED", () => {
    expect(copy("REMOTE_ACCESS_OPENED", { app: "TeamViewer" }).title).toBe("Margaret opened TeamViewer");
    expect(copy("CONTROL_APP_INSTALLED", { app: "Screen Helper" })).toEqual({
      title: "A new app can control Margaret’s screen",
      body: "Screen Helper was installed and can control the screen. Margaret saw a short warning about it on the phone. A quick call will tell you if they meant to install it.",
    });
  });

  it("HELP_REQUESTED with and without a note", () => {
    expect(copy("HELP_REQUESTED", { note: "Is this real?" }, "URGENT")).toEqual({
      title: "Margaret asked for help",
      body: "Margaret sent you a picture of their screen with a note: “Is this real?”",
    });
    expect(copy("HELP_REQUESTED").body).toBe("Margaret sent you a picture of their screen.");
  });

  it("REMINDER_MISSED", () => {
    expect(copy("REMINDER_MISSED", { title: "Blood pressure pill", time: "08:00" })).toEqual({
      title: "Margaret hasn’t confirmed a reminder",
      body: "Margaret hasn’t tapped Done for “Blood pressure pill” yet. It was due at 8:00 AM.",
    });
    expect(copy("REMINDER_MISSED").body).toBe("Margaret hasn’t tapped Done for a reminder yet.");
  });

  it("PROTECTION_CHANGED_BY_PARENT names the feature", () => {
    expect(copy("PROTECTION_CHANGED_BY_PARENT", { feature: "longCall", enabled: "false" })).toEqual({
      title: "Margaret turned off long-call notices",
      body: "Margaret turned off long-call notices on their phone. That’s up to them. You could ask how it’s going next time you talk.",
    });
    expect(copy("PROTECTION_CHANGED_BY_PARENT", { feature: "screening", enabled: "true" }).title).toBe(
      "Margaret turned on unknown caller screening",
    );
    expect(copy("PROTECTION_CHANGED_BY_PARENT", { feature: "all" }).title).toBe("Margaret paused Guardian Mode");
    expect(copy("PROTECTION_CHANGED_BY_PARENT", { feature: "mystery" }).title).toBe(
      "Margaret turned off a Guardian Mode feature",
    );
  });

  it("PERMISSION_MISSING explains the effect", () => {
    expect(copy("PERMISSION_MISSING", { permission: "usageAccess" })).toEqual({
      title: "Margaret’s phone needs a setting turned back on",
      body: "Usage access is off, so pause cards can’t appear. You can help Margaret turn it back on next time you talk.",
    });
    expect(copy("PERMISSION_MISSING", { permission: "unknown" }).body).toContain("A setting Guardian Mode needs");
  });

  it("CHECK_IN_MISSED, daily and silent", () => {
    expect(copy("CHECK_IN_MISSED", { deadline: "11:00" })).toEqual({
      title: "Margaret’s phone hasn’t been used yet today",
      body: "Margaret’s phone hadn’t been picked up by 11:00 AM today. It may be nothing. A quick call will tell you.",
    });
    expect(copy("CHECK_IN_MISSED", { reason: "silent" }, "URGENT")).toEqual({
      title: "Margaret’s phone has been quiet for a day",
      body: "We haven’t heard from Margaret’s phone in over 24 hours. It may be switched off or out of battery. A quick call will tell you.",
    });
  });

  it("falls back gracefully without a parent name or app", () => {
    const c = notificationCopy({ type: "PAUSE_SHOWN", severity: "NOTICE", data: {} }, { parentName: "  " });
    expect(c.title).toBe("Your parent paused before opening an app");
  });

  it("possessive of a name ending in s", () => {
    expect(notificationCopy({ type: "CHECK_IN_MISSED", severity: "NOTICE", data: {} }, { parentName: "Doris" }).title).toBe(
      "Doris’ phone hasn’t been used yet today",
    );
  });

  describe("tone rules hold for every type and severity", () => {
    const samples: Record<string, string> = {
      app: "Chase",
      minutes: "25",
      minutesSinceCall: "3",
      duringCall: "true",
      afterUnknownCall: "true",
      ongoing: "true",
      note: "I'm stuck",
      title: "Pill",
      feature: "pause",
      permission: "overlay",
      reason: "silent",
    };
    for (const type of EVENT_TYPES) {
      for (const severity of SEVERITIES) {
        it(`${type} / ${severity}`, () => {
          const c = notificationCopy({ type, severity, data: samples }, ctx);
          const text = `${c.title} ${c.body}`;
          expect(c.title.length).toBeGreaterThan(0);
          expect(c.title.length).toBeLessThanOrEqual(65);
          expect(c.body.length).toBeLessThanOrEqual(240);
          expect(text).toContain("Margaret");
          expect(text).not.toMatch(/\b(scam|fraud|danger|dangerous|alert|alarm|warning!|urgent)\b/i);
          expect(text).not.toMatch(/\+?\d{3}[ -]?\d{3}[ -]?\d{4}/); // never a phone number
          expect(text).not.toContain("undefined");
          expect(text).not.toContain("null");
        });
      }
    }
  });
});

describe("friendlyTime", () => {
  it.each([
    ["00:00", "12:00 AM"],
    ["08:05", "8:05 AM"],
    ["11:00", "11:00 AM"],
    ["12:00", "12:00 PM"],
    ["14:30", "2:30 PM"],
    ["23:59", "11:59 PM"],
    ["7:15", "7:15 AM"],
    ["24:00", "24:00"],
    ["soon", "soon"],
  ])("%s → %s", (input, output) => {
    expect(friendlyTime(input)).toBe(output);
  });
});
