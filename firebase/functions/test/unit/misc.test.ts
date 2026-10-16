import { Timestamp } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { describe, expect, it } from "vitest";
import { callerOf, parseInput, requireHelper, requirePhone } from "../../src/lib/auth.js";
import { assertUsable, MAX_FAILED_ATTEMPTS, type Invite } from "../../src/lib/invites.js";
import { FakeMessenger, toMulticast } from "../../src/lib/messaging.js";
import { memberStatus } from "../../src/lib/members.js";
import { DEVICE_INVITE_TTL_MS, planLimits } from "../../src/lib/model.js";
import { featuresTurnedOff } from "../../src/lib/protectionChanges.js";
import { isLimited, RATE_WINDOW_MS } from "../../src/lib/rateLimit.js";
import { acceptHelperInviteInput, approveHelperInput, createFamilyInput } from "../../src/lib/schemas.js";
import { checkInDeviceFrom } from "../../src/triggers/checkInSweep.js";
import { fixtureConfig } from "./fixture.js";

describe("toMulticast", () => {
  it("builds a notification message for helpers", () => {
    const m = toMulticast(["t1"], {
      notification: { title: "T", body: "B" },
      data: { type: "event" },
      highPriority: true,
      tag: "ev1",
      link: "https://example.test",
    });
    expect(m.notification).toEqual({ title: "T", body: "B" });
    expect(m.android).toEqual({ priority: "high", collapseKey: "ev1", notification: { tag: "ev1" } });
    expect(m.webpush).toEqual({
      headers: { Urgency: "high" },
      notification: { title: "T", body: "B", tag: "ev1" },
      fcmOptions: { link: "https://example.test" },
    });
  });

  it("builds a data-only wake-up for the phone", () => {
    const m = toMulticast(["t1"], { data: { type: "config" }, highPriority: true });
    expect(m.notification).toBeUndefined();
    expect(m.data).toEqual({ type: "config" });
    expect(m.android?.priority).toBe("high");
  });
});

describe("FakeMessenger", () => {
  it("delivers to every token except dead- ones", async () => {
    const fake = new FakeMessenger();
    const out = await fake.send(["good", "dead-1"], { data: {}, highPriority: false });
    expect(out).toEqual([
      { token: "good", ok: true, tokenDead: false },
      { token: "dead-1", ok: false, tokenDead: true, error: "messaging/registration-token-not-registered" },
    ]);
    expect(fake.sent).toHaveLength(1);
  });
});

describe("assertUsable", () => {
  const now = new Date("2026-10-16T12:00:00Z");
  const invite = (o: Partial<Invite> = {}): Invite => ({
    code: "ABCDEF",
    familyId: "f",
    deviceId: "d",
    kind: "device",
    createdBy: "h",
    createdByName: "Sarah",
    expiresAt: new Date(now.getTime() + DEVICE_INVITE_TTL_MS),
    usedBy: null,
    failedAttempts: 0,
    ...o,
  });
  const reason = (fn: () => void): unknown => {
    try {
      fn();
    } catch (e) {
      return (e as HttpsError).details;
    }
    return null;
  };

  it("accepts a fresh code", () => {
    expect(() => { assertUsable(invite(), "device", "phone", now); }).not.toThrow();
  });
  it("device codes last 15 minutes", () => {
    expect(DEVICE_INVITE_TTL_MS).toBe(15 * 60 * 1000);
  });
  it("rejects expired, used, wrong kind and locked codes", () => {
    expect(reason(() => { assertUsable(invite({ expiresAt: now }), "device", "p", now); })).toEqual({ reason: "expired" });
    expect(reason(() => { assertUsable(invite({ usedBy: "other" }), "device", "p", now); })).toEqual({ reason: "used" });
    expect(reason(() => { assertUsable(invite({ kind: "helper" }), "device", "p", now); })).toEqual({ reason: "wrong-kind" });
    expect(reason(() => { assertUsable(invite({ failedAttempts: MAX_FAILED_ATTEMPTS }), "device", "p", now); })).toEqual({
      reason: "locked",
    });
    expect(MAX_FAILED_ATTEMPTS).toBe(5);
  });
  it("lets the same caller retry a code they already used", () => {
    expect(() => { assertUsable(invite({ usedBy: "p", expiresAt: new Date(0) }), "device", "p", now); }).not.toThrow();
  });
});

describe("isLimited", () => {
  const now = 10 * RATE_WINDOW_MS + 5_000;
  const start = 10 * RATE_WINDOW_MS;
  it("counts failures within the current window only", () => {
    expect(isLimited(undefined, 10, now)).toBe(false);
    expect(isLimited({ windowStart: start, failures: 9 }, 10, now)).toBe(false);
    expect(isLimited({ windowStart: start, failures: 10 }, 10, now)).toBe(true);
    expect(isLimited({ windowStart: start - RATE_WINDOW_MS, failures: 99 }, 10, now)).toBe(false);
  });
});

describe("auth helpers", () => {
  const req = (provider: string, extra: Record<string, unknown> = {}) =>
    ({ auth: { uid: "u1", token: { firebase: { sign_in_provider: provider }, ...extra } } }) as never;

  it("rejects unauthenticated callers", () => {
    expect(() => callerOf({})).toThrow(HttpsError);
  });
  it("helpers can't be anonymous and phones must be", () => {
    expect(() => requireHelper(req("anonymous"))).toThrow(/sign in/);
    expect(requireHelper(req("google.com", { email: "s@example.com", name: "Sarah" }))).toMatchObject({
      uid: "u1",
      anonymous: false,
      name: "Sarah",
    });
    expect(() => requirePhone(req("google.com"))).toThrow(/phone/);
    expect(requirePhone(req("anonymous")).anonymous).toBe(true);
  });
  it("parseInput reports the first problem as invalid-argument", () => {
    expect(() => parseInput(createFamilyInput, { familyName: "", parentName: "Mom" })).toThrow(
      expect.objectContaining({ code: "invalid-argument" }),
    );
  });
});

describe("input schemas", () => {
  it("createFamily trims names, drops empty phone, validates time zone", () => {
    expect(parseInput(createFamilyInput, { familyName: " Smiths ", parentName: "Mom", helperPhone: "" })).toEqual({
      familyName: "Smiths",
      parentName: "Mom",
    });
    expect(parseInput(createFamilyInput, { familyName: "S", parentName: "M", helperPhone: "+1 (555) 010-1111" }).helperPhone).toBe(
      "+1 (555) 010-1111",
    );
    expect(() => parseInput(createFamilyInput, { familyName: "S", parentName: "M", helperPhone: "call me" })).toThrow();
    expect(() => parseInput(createFamilyInput, { familyName: "S", parentName: "M", timezone: "Nope/Nope" })).toThrow();
    expect(() => parseInput(createFamilyInput, { familyName: "S".repeat(81), parentName: "M" })).toThrow();
  });
  it("acceptHelperInvite and approveHelper", () => {
    expect(parseInput(acceptHelperInviteInput, { code: "abc234", displayName: "David" })).toEqual({
      code: "abc234",
      displayName: "David",
    });
    expect(() => parseInput(approveHelperInput, { familyId: "f", uid: "u" })).toThrow();
    expect(() => parseInput(approveHelperInput, { familyId: "f/../x", uid: "u", approve: true })).toThrow();
  });
});

describe("featuresTurnedOff", () => {
  it("lists only features that went from on to off", () => {
    const before = fixtureConfig();
    const after = structuredClone(before);
    after.protection.longCallAlert.enabled = false;
    after.protection.callScreening.mode = "OFF";
    after.checkIn.enabled = false;
    after.display.fontScale = 2;
    expect(featuresTurnedOff(before, after)).toEqual(["screening", "longCall", "checkIn"]);
    expect(featuresTurnedOff(after, before)).toEqual([]);
  });
});

describe("misc", () => {
  it("plan limits", () => {
    expect(planLimits("family")).toEqual({ devices: 2, helpers: 4 });
    expect(planLimits("free")).toEqual({ devices: 1, helpers: 1 });
    expect(planLimits(undefined)).toEqual({ devices: 1, helpers: 1 });
  });
  it("memberStatus treats docs without status as active", () => {
    expect(memberStatus({ status: "pending" })).toBe("pending");
    expect(memberStatus({ status: "active" })).toBe("active");
    expect(memberStatus({})).toBe("active");
  });
  it("checkInDeviceFrom reads a device document", () => {
    const hb = Timestamp.fromMillis(1_000);
    expect(
      checkInDeviceFrom({
        deviceUid: "p",
        checkIn: { enabled: true, deadline: "10:00", timezone: "UTC" },
        status: { lastHeartbeatAt: hb },
        checkInNotifiedFor: "2026-10-16",
        silentNotifiedAt: null,
      }),
    ).toEqual({
      deviceUid: "p",
      checkIn: { enabled: true, deadline: "10:00", timezone: "UTC" },
      lastHeartbeatAt: new Date(1_000),
      checkInNotifiedFor: "2026-10-16",
      silentNotifiedAt: null,
    });
    expect(checkInDeviceFrom(undefined)).toMatchObject({ deviceUid: null, checkIn: null, lastHeartbeatAt: null });
  });
});
