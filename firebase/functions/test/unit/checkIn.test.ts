import { describe, expect, it } from "vitest";
import { evaluateCheckIn, isValidTimeZone, parseDeadline, type CheckInDevice } from "../../src/lib/checkIn.js";

const HOUR = 3_600_000;

function device(overrides: Partial<CheckInDevice> = {}): CheckInDevice {
  return {
    deviceUid: "phone-uid",
    checkIn: { enabled: true, deadline: "11:00", timezone: "America/New_York" },
    lastHeartbeatAt: null,
    checkInNotifiedFor: null,
    silentNotifiedAt: null,
    ...overrides,
  };
}

const at = (iso: string) => new Date(iso);

describe("evaluateCheckIn: basics", () => {
  it("does nothing when check-in is off", () => {
    const d = device({ checkIn: { enabled: false, deadline: "11:00", timezone: "America/New_York" } });
    expect(evaluateCheckIn(d, at("2026-10-16T20:00:00Z"))).toEqual({ kind: "none", reason: "disabled" });
  });

  it("does nothing without settings", () => {
    expect(evaluateCheckIn(device({ checkIn: null }), at("2026-10-16T20:00:00Z"))).toEqual({
      kind: "none",
      reason: "disabled",
    });
  });

  it("does nothing for a device no phone has claimed", () => {
    expect(evaluateCheckIn(device({ deviceUid: null }), at("2026-10-16T20:00:00Z"))).toEqual({
      kind: "none",
      reason: "unpaired",
    });
  });

  it("does nothing for an unknown time zone", () => {
    const d = device({ checkIn: { enabled: true, deadline: "11:00", timezone: "Mars/Olympus_Mons" } });
    expect(evaluateCheckIn(d, at("2026-10-16T20:00:00Z"))).toEqual({ kind: "none", reason: "invalid-timezone" });
  });

  it("waits until the deadline", () => {
    // 10:59 in New York (EDT, UTC-4)
    const d = device({ lastHeartbeatAt: at("2026-10-15T22:00:00Z") });
    expect(evaluateCheckIn(d, at("2026-10-16T14:59:00Z"))).toEqual({ kind: "none", reason: "before-deadline" });
  });

  it("fires at the deadline when the phone hasn't been used since local midnight", () => {
    const d = device({ lastHeartbeatAt: at("2026-10-16T03:59:00Z") }); // 23:59 the day before, local
    expect(evaluateCheckIn(d, at("2026-10-16T15:00:00Z"))).toEqual({
      kind: "missed",
      localDate: "2026-10-16",
      deadline: "11:00",
    });
  });

  it("stays quiet when the phone was used after local midnight", () => {
    const d = device({ lastHeartbeatAt: at("2026-10-16T04:00:00Z") }); // 00:00 local
    expect(evaluateCheckIn(d, at("2026-10-16T15:30:00Z"))).toEqual({ kind: "none", reason: "used-today" });
  });

  it("sends the daily notice once per local day", () => {
    const d = device({ lastHeartbeatAt: at("2026-10-15T20:00:00Z"), checkInNotifiedFor: "2026-10-16" });
    expect(evaluateCheckIn(d, at("2026-10-16T18:00:00Z"))).toEqual({ kind: "none", reason: "already-notified" });
  });

  it("fires again the next day if the phone is still unused", () => {
    const d = device({
      lastHeartbeatAt: at("2026-10-16T03:00:00Z"),
      checkInNotifiedFor: "2026-10-16",
      silentNotifiedAt: at("2026-10-17T03:15:00Z"),
    });
    expect(evaluateCheckIn(d, at("2026-10-17T15:00:00Z"))).toEqual({
      kind: "missed",
      localDate: "2026-10-17",
      deadline: "11:00",
    });
  });

  it("treats a phone that never sent a heartbeat as unused today", () => {
    expect(evaluateCheckIn(device(), at("2026-10-16T16:00:00Z"))).toMatchObject({ kind: "missed" });
  });

  it("falls back to 11:00 for a malformed deadline", () => {
    const d = device({ checkIn: { enabled: true, deadline: "25:99", timezone: "America/New_York" } });
    expect(evaluateCheckIn(d, at("2026-10-16T14:30:00Z"))).toEqual({ kind: "none", reason: "before-deadline" });
    expect(evaluateCheckIn(d, at("2026-10-16T15:00:00Z"))).toMatchObject({ kind: "missed", deadline: "11:00" });
  });
});

describe("evaluateCheckIn: time zones", () => {
  const now = at("2026-10-16T12:00:00Z");

  it.each([
    // [timezone, expected local date at 12:00Z, past an 11:00 deadline?]
    ["Europe/London", "2026-10-16", true], // 13:00 BST
    ["America/Chicago", "2026-10-16", false], // 07:00 CDT
    ["Asia/Tokyo", "2026-10-16", true], // 21:00 JST
    ["Pacific/Auckland", "2026-10-17", false], // 01:00 NZDT next day
    ["Asia/Kolkata", "2026-10-16", true], // 17:30 IST (half-hour offset)
    ["Pacific/Honolulu", "2026-10-16", false], // 02:00 HST
  ])("%s", (timezone, localDate, past) => {
    const d = device({ checkIn: { enabled: true, deadline: "11:00", timezone }, lastHeartbeatAt: null });
    const decision = evaluateCheckIn(d, now);
    if (past) expect(decision).toEqual({ kind: "missed", localDate, deadline: "11:00" });
    else expect(decision).toEqual({ kind: "none", reason: "before-deadline" });
  });

  it("uses the parent's midnight, not UTC midnight, for 'used today'", () => {
    // Tokyo: heartbeat at 08:00 JST Oct 16 (= 23:00Z Oct 15) counts as today in Tokyo.
    const d = device({
      checkIn: { enabled: true, deadline: "11:00", timezone: "Asia/Tokyo" },
      lastHeartbeatAt: at("2026-10-15T23:00:00Z"),
    });
    expect(evaluateCheckIn(d, at("2026-10-16T03:00:00Z"))).toEqual({ kind: "none", reason: "used-today" });
  });

  it("a heartbeat from late yesterday in the parent's zone does not count", () => {
    // Los Angeles: 23:30 PDT Oct 15 = 06:30Z Oct 16. At 11:30 PDT Oct 16 that is yesterday.
    const d = device({
      checkIn: { enabled: true, deadline: "11:00", timezone: "America/Los_Angeles" },
      lastHeartbeatAt: at("2026-10-16T06:30:00Z"),
    });
    expect(evaluateCheckIn(d, at("2026-10-16T18:30:00Z"))).toMatchObject({ kind: "missed", localDate: "2026-10-16" });
  });
});

describe("evaluateCheckIn: daylight saving", () => {
  const ny = (deadline: string) => ({ enabled: true, deadline, timezone: "America/New_York" });

  it("spring forward: 11:00 EDT is 15:00Z on the change day", () => {
    // 2026-03-08 clocks go 02:00 → 03:00 in New York.
    const d = device({ checkIn: ny("11:00"), lastHeartbeatAt: at("2026-03-07T20:00:00Z") });
    expect(evaluateCheckIn(d, at("2026-03-08T14:59:00Z"))).toEqual({ kind: "none", reason: "before-deadline" });
    expect(evaluateCheckIn(d, at("2026-03-08T15:00:00Z"))).toMatchObject({ kind: "missed", localDate: "2026-03-08" });
  });

  it("spring forward: a deadline inside the skipped hour moves forward with the clocks", () => {
    // 02:30 doesn't exist on 2026-03-08; it behaves as 03:30 EDT = 07:30Z.
    const d = device({ checkIn: ny("02:30"), lastHeartbeatAt: at("2026-03-07T20:00:00Z") });
    expect(evaluateCheckIn(d, at("2026-03-08T07:29:00Z"))).toEqual({ kind: "none", reason: "before-deadline" });
    expect(evaluateCheckIn(d, at("2026-03-08T07:30:00Z"))).toMatchObject({ kind: "missed", deadline: "02:30" });
  });

  it("fall back: 11:00 EST is 16:00Z on the change day", () => {
    // 2026-11-01 clocks go 02:00 → 01:00 in New York.
    const d = device({ checkIn: ny("11:00"), lastHeartbeatAt: at("2026-10-31T20:00:00Z") });
    expect(evaluateCheckIn(d, at("2026-11-01T15:30:00Z"))).toEqual({ kind: "none", reason: "before-deadline" });
    expect(evaluateCheckIn(d, at("2026-11-01T16:00:00Z"))).toMatchObject({ kind: "missed", localDate: "2026-11-01" });
  });

  it("fall back: a deadline in the repeated hour fires on its first occurrence", () => {
    // 01:30 happens twice on 2026-11-01: 05:30Z (EDT) and 06:30Z (EST).
    const d = device({ checkIn: ny("01:30"), lastHeartbeatAt: at("2026-10-31T20:00:00Z") });
    expect(evaluateCheckIn(d, at("2026-11-01T05:29:00Z"))).toEqual({ kind: "none", reason: "before-deadline" });
    expect(evaluateCheckIn(d, at("2026-11-01T05:30:00Z"))).toMatchObject({ kind: "missed" });
  });

  it("local midnight on the change day is the right instant", () => {
    // Midnight 2026-03-08 in New York is still EST: 05:00Z.
    const d = device({ checkIn: ny("11:00"), lastHeartbeatAt: at("2026-03-08T04:59:00Z") });
    expect(evaluateCheckIn(d, at("2026-03-08T16:00:00Z"))).toMatchObject({ kind: "missed" });
    const used = device({ checkIn: ny("11:00"), lastHeartbeatAt: at("2026-03-08T05:00:00Z") });
    expect(evaluateCheckIn(used, at("2026-03-08T16:00:00Z"))).toEqual({ kind: "none", reason: "used-today" });
  });

  it("southern hemisphere: Sydney's DST start (2026-10-04)", () => {
    // Clocks go 02:00 → 03:00 AEST→AEDT. 11:00 AEDT = 00:00Z.
    const d = device({
      checkIn: { enabled: true, deadline: "11:00", timezone: "Australia/Sydney" },
      lastHeartbeatAt: at("2026-10-03T03:00:00Z"), // 13:00 AEST the day before
    });
    expect(evaluateCheckIn(d, at("2026-10-03T23:59:00Z"))).toEqual({ kind: "none", reason: "before-deadline" });
    expect(evaluateCheckIn(d, at("2026-10-04T00:00:00Z"))).toMatchObject({ kind: "missed", localDate: "2026-10-04" });
  });

  it("a zone whose DST change happens at midnight still has a start of day", () => {
    // America/Santiago springs forward at 00:00 → 01:00 (2026-09-06).
    const d = device({
      checkIn: { enabled: true, deadline: "11:00", timezone: "America/Santiago" },
      lastHeartbeatAt: at("2026-09-05T20:00:00Z"), // 16:00 the day before
    });
    expect(evaluateCheckIn(d, at("2026-09-06T15:00:00Z"))).toMatchObject({ kind: "missed", localDate: "2026-09-06" });
  });
});

describe("evaluateCheckIn: silent phone", () => {
  const hb = at("2026-10-15T16:00:00Z"); // 12:00 EDT

  it("not silent at exactly 24 hours", () => {
    const d = device({ lastHeartbeatAt: hb, checkInNotifiedFor: "2026-10-16" });
    expect(evaluateCheckIn(d, new Date(hb.getTime() + 24 * HOUR))).toEqual({ kind: "none", reason: "already-notified" });
  });

  it("silent after more than 24 hours, and covers the daily notice", () => {
    const d = device({ lastHeartbeatAt: hb });
    expect(evaluateCheckIn(d, new Date(hb.getTime() + 24 * HOUR + 60_000))).toEqual({
      kind: "silent",
      localDate: "2026-10-16",
      markDay: true,
    });
  });

  it("silent notice does not mark the day when the daily one was already sent", () => {
    const d = device({ lastHeartbeatAt: hb, checkInNotifiedFor: "2026-10-16" });
    expect(evaluateCheckIn(d, new Date(hb.getTime() + 25 * HOUR))).toEqual({
      kind: "silent",
      localDate: "2026-10-16",
      markDay: false,
    });
  });

  it("silent notice once per silent stretch", () => {
    const d = device({
      lastHeartbeatAt: hb,
      silentNotifiedAt: new Date(hb.getTime() + 25 * HOUR),
      checkInNotifiedFor: "2026-10-16",
    });
    expect(evaluateCheckIn(d, new Date(hb.getTime() + 30 * HOUR))).toEqual({ kind: "none", reason: "already-notified" });
  });

  it("a new silent stretch after the phone came back gets a new notice", () => {
    const back = at("2026-10-20T14:00:00Z");
    const d = device({
      lastHeartbeatAt: back,
      silentNotifiedAt: new Date(hb.getTime() + 25 * HOUR),
      checkInNotifiedFor: "2026-10-21",
    });
    expect(evaluateCheckIn(d, new Date(back.getTime() + 26 * HOUR))).toMatchObject({ kind: "silent" });
  });

  it("never-seen phones are not 'silent' (no heartbeat to measure from)", () => {
    expect(evaluateCheckIn(device(), at("2026-10-16T13:00:00Z"))).toEqual({ kind: "none", reason: "before-deadline" });
  });
});

describe("helpers", () => {
  it("parseDeadline", () => {
    expect(parseDeadline("07:05")).toEqual({ hour: 7, minute: 5 });
    expect(parseDeadline(" 23:59 ")).toEqual({ hour: 23, minute: 59 });
    expect(parseDeadline("7:05")).toEqual({ hour: 11, minute: 0 });
    expect(parseDeadline("")).toEqual({ hour: 11, minute: 0 });
  });

  it("isValidTimeZone", () => {
    expect(isValidTimeZone("America/Chicago")).toBe(true);
    expect(isValidTimeZone("UTC")).toBe(true);
    expect(isValidTimeZone("Nowhere/Special")).toBe(false);
    expect(isValidTimeZone("")).toBe(false);
  });
});
