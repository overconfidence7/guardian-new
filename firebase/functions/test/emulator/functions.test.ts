/*
 * Callables and triggers end to end against the Emulator Suite: the functions run in the
 * functions emulator (FCM replaced by the fake messenger), clients use the real web SDK.
 */
import { Timestamp as AdminTimestamp } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";
import { getApp } from "firebase-admin/app";
import { addDoc, collection, doc, serverTimestamp, setDoc, updateDoc } from "firebase/firestore";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { runCheckInSweep } from "../../src/triggers/checkInSweep.js";
import { admin, closeAll, errorCode, errorReason, eventually, helperClient, phoneClient, type Client } from "./harness.js";

let sarah: Client; // creates the family (owner)
let david: Client; // joins as a second helper
let phone: Client; // Margaret's phone
let familyId: string;
let deviceId: string;

const familyRef = () => admin().doc(`families/${familyId}`);
const deviceRef = () => admin().doc(`families/${familyId}/devices/${deviceId}`);
const configPath = () => `families/${familyId}/devices/${deviceId}/config/current`;

beforeAll(async () => {
  [sarah, david, phone] = await Promise.all([helperClient("Sarah"), helperClient("David"), phoneClient()]);
});

afterAll(async () => {
  await closeAll();
});

describe("pairing: createFamily → createDeviceInvite → previewInvite → claimDeviceInvite", () => {
  it("refuses signed-out and anonymous callers", async () => {
    expect(await errorCode(phone.call("createFamily", { familyName: "X", parentName: "Y" }))).toBe("permission-denied");
  });

  it("validates input", async () => {
    expect(await errorCode(sarah.call("createFamily", { familyName: "", parentName: "Margaret" }))).toBe("invalid-argument");
  });

  it("creates the family, owner member, device and default config", async () => {
    const out = await sarah.call<{ familyId: string; deviceId: string }>("createFamily", {
      familyName: "The Smiths",
      parentName: "Margaret",
      helperPhone: "+1 555 010 1111",
      timezone: "America/Chicago",
    });
    familyId = out.familyId;
    deviceId = out.deviceId;

    const family = (await familyRef().get()).data()!;
    expect(family).toMatchObject({ name: "The Smiths", plan: "free", ownerUid: sarah.uid, memberUids: [sarah.uid] });
    const member = (await familyRef().collection("members").doc(sarah.uid).get()).data()!;
    expect(member).toMatchObject({ role: "owner", status: "active", displayName: "Sarah", phone: "+1 555 010 1111" });
    const device = (await deviceRef().get()).data()!;
    expect(device).toMatchObject({ parentName: "Margaret", deviceUid: null, checkIn: { timezone: "America/Chicago" } });
    const config = (await admin().doc(configPath()).get()).data()!;
    expect(config.parentName).toBe("Margaret");
    expect(config.family).toEqual([
      expect.objectContaining({ id: `fam_${sarah.uid}`, name: "Sarah", isPrimaryHelper: true }),
    ]);
    expect((config.home as { tiles: { kind: string }[] }).tiles.map((t) => t.kind)).toEqual([
      "CALL_CONTACT", "PHONE", "MESSAGES", "CAMERA", "PHOTOS", "REMINDERS", "HELP",
    ]);
    expect(config.updatedBy).toEqual({ uid: sarah.uid, name: "Sarah", kind: "helper" });
  });

  it("enforces the device limit of the free plan", async () => {
    expect(await errorReason(sarah.call("addDevice", { familyId, parentName: "Walter" }))).toBe("plan-limit");
  });

  let code: string;

  it("makes a 15-minute device code", async () => {
    const before = Date.now();
    const out = await sarah.call<{ code: string; expiresAt: number }>("createDeviceInvite", { familyId, deviceId });
    code = out.code;
    expect(code).toMatch(/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/);
    expect(out.expiresAt - before).toBeGreaterThan(14 * 60_000);
    expect(out.expiresAt - before).toBeLessThanOrEqual(15 * 60_000 + 5_000);
  });

  it("non-members can't make codes", async () => {
    expect(await errorCode(david.call("createDeviceInvite", { familyId, deviceId }))).toBe("not-found");
  });

  it("previews the code on the phone", async () => {
    expect(await phone.call("previewInvite", { code: code.toLowerCase() })).toEqual({
      kind: "device",
      familyName: "The Smiths",
      parentName: "Margaret",
      helperName: "Sarah",
    });
  });

  it("helpers can't claim a device code", async () => {
    expect(await errorCode(david.call("claimDeviceInvite", { code, model: "x" }))).toBe("permission-denied");
  });

  it("the phone claims the code (as an anonymous user)", async () => {
    const out = await phone.call("claimDeviceInvite", { code, model: "Moto G", androidVersion: "14", appVersion: "1.0.0" });
    expect(out).toEqual({ familyId, deviceId });
    const device = (await deviceRef().get()).data()!;
    expect(device).toMatchObject({ deviceUid: phone.uid, model: "Moto G", androidVersion: "14", appVersion: "1.0.0" });
    expect((device.status as { lastHeartbeatAt: unknown }).lastHeartbeatAt).toBeInstanceOf(AdminTimestamp);
    expect((await familyRef().get()).get("deviceUids")).toEqual([phone.uid]);
    expect((await admin().doc(`invites/${code}`).get()).get("usedBy")).toBe(phone.uid);
  });

  it("a retried claim by the same phone succeeds; codes are single use for anyone else", async () => {
    expect(await phone.call("claimDeviceInvite", { code })).toEqual({ familyId, deviceId });
    const other = await phoneClient();
    expect(await errorReason(other.call("claimDeviceInvite", { code }))).toBe("used");
  });

  it("an expired code is refused", async () => {
    const out = await sarah.call<{ code: string }>("createDeviceInvite", { familyId, deviceId });
    await admin().doc(`invites/${out.code}`).update({ expiresAt: AdminTimestamp.fromMillis(Date.now() - 1000) });
    const other = await phoneClient();
    expect(await errorReason(other.call("previewInvite", { code: out.code }))).toBe("expired");
  });
});

describe("onEventCreated", () => {
  beforeAll(async () => {
    await familyRef().collection("members").doc(sarah.uid).update({ pushTokens: ["tok-sarah", "dead-sarah-old"] });
  });

  it("the phone writes an event; helpers are notified and dead tokens are removed", async () => {
    const ref = await addDoc(collection(phone.db, `families/${familyId}/devices/${deviceId}/events`), {
      type: "PAUSE_SHOWN",
      severity: "NOTICE",
      at: Date.now(),
      data: { app: "Chase", duringCall: "false", minutesSinceCall: "4" },
      createdAt: serverTimestamp(),
      source: "device",
    });
    const delivered = await eventually(async () => {
      const snap = await admin().doc(ref.path).get();
      return snap.get("deliveredTo") as string[] | undefined;
    });
    expect(delivered).toEqual([sarah.uid]);
    expect((await admin().doc(ref.path).get()).get("expireAt")).toBeInstanceOf(AdminTimestamp);
    const tokens = await eventually(async () => {
      const t = (await familyRef().collection("members").doc(sarah.uid).get()).get("pushTokens") as string[];
      return t.length === 1 ? t : undefined;
    });
    expect(tokens).toEqual(["tok-sarah"]);
  });

  it("INFO events are recorded but not pushed", async () => {
    const ref = await addDoc(collection(phone.db, `families/${familyId}/devices/${deviceId}/events`), {
      type: "PERMISSION_MISSING",
      severity: "INFO",
      at: Date.now(),
      data: { permission: "overlay" },
      createdAt: serverTimestamp(),
      source: "device",
    });
    expect(await eventually(async () => (await admin().doc(ref.path).get()).get("deliveredTo") as string[] | undefined)).toEqual([]);
  });

  it("NOTICE respects notify.notice = off; URGENT always goes out", async () => {
    await familyRef().collection("members").doc(sarah.uid).update({ "notify.notice": "off" });
    const events = collection(phone.db, `families/${familyId}/devices/${deviceId}/events`);
    const base = { at: Date.now(), createdAt: serverTimestamp(), source: "device" };
    const notice = await addDoc(events, { ...base, type: "PAUSE_CONTINUED", severity: "NOTICE", data: { app: "Chase" } });
    const urgent = await addDoc(events, { ...base, type: "HELP_REQUESTED", severity: "URGENT", data: { note: "Call me" } });
    const read = (path: string) => eventually(async () => (await admin().doc(path).get()).get("deliveredTo") as string[] | undefined);
    expect(await read(notice.path)).toEqual([]);
    expect(await read(urgent.path)).toEqual([sarah.uid]);
    await familyRef().collection("members").doc(sarah.uid).update({ "notify.notice": "push" });
  });
});

describe("helpers join only with the parent's approval", () => {
  let code: string;

  it("the free plan has room for one helper", async () => {
    expect(await errorReason(sarah.call("createHelperInvite", { familyId }))).toBe("plan-limit");
    await familyRef().update({ plan: "family" });
    code = (await sarah.call<{ code: string }>("createHelperInvite", { familyId })).code;
  });

  it("the phone can't accept a helper invite", async () => {
    expect(await errorCode(phone.call("acceptHelperInvite", { code, displayName: "Phone" }))).toBe("permission-denied");
  });

  it("accepting creates a pending member outside memberUids", async () => {
    const out = await david.call("acceptHelperInvite", { code, displayName: "David", phone: "+1 555 010 2222" });
    expect(out).toEqual({ familyId, status: "pending" });
    expect((await familyRef().get()).get("memberUids")).toEqual([sarah.uid]);
    const member = (await familyRef().collection("members").doc(david.uid).get()).data()!;
    expect(member).toMatchObject({ role: "helper", status: "pending", displayName: "David" });
    expect(await david.call("acceptHelperInvite", { code, displayName: "David" })).toEqual({ familyId, status: "pending" });
  });

  it("pending helpers get no pushes", async () => {
    await familyRef().collection("members").doc(david.uid).update({ pushTokens: ["tok-david"] });
    const ref = await addDoc(collection(phone.db, `families/${familyId}/devices/${deviceId}/events`), {
      type: "LONG_UNKNOWN_CALL",
      severity: "URGENT",
      at: Date.now(),
      data: { minutes: "20", ongoing: "true" },
      createdAt: serverTimestamp(),
      source: "device",
    });
    expect(await eventually(async () => (await admin().doc(ref.path).get()).get("deliveredTo") as string[] | undefined)).toEqual([
      sarah.uid,
    ]);
  });

  it("only the family's phone approves", async () => {
    expect(await errorCode(sarah.call("approveHelper", { familyId, uid: david.uid, approve: true }))).toBe("permission-denied");
    const stranger = await phoneClient();
    expect(await errorCode(stranger.call("approveHelper", { familyId, uid: david.uid, approve: true }))).toBe(
      "permission-denied",
    );
  });

  it("approval activates the helper and adds them to the phone's family list", async () => {
    expect(await phone.call("approveHelper", { familyId, uid: david.uid, approve: true })).toEqual({});
    expect((await familyRef().get()).get("memberUids")).toEqual([sarah.uid, david.uid]);
    expect((await familyRef().collection("members").doc(david.uid).get()).get("status")).toBe("active");
    const family = (await admin().doc(configPath()).get()).get("family") as { id: string; phone: string }[];
    expect(family.map((f) => f.id)).toEqual([`fam_${sarah.uid}`, `fam_${david.uid}`]);
    expect(await phone.call("approveHelper", { familyId, uid: david.uid, approve: true })).toEqual({});
  });

  it("every helper is told David was added, and the phone lists him in Recent changes (SECURITY.md §3)", async () => {
    const notice = await helperNotice("added", "David");
    expect(notice.data()).toMatchObject({ severity: "URGENT", source: "server" });
    expect(notice.get("deliveredTo")).toContain(sarah.uid);
    // The retried approval above didn't announce him twice.
    const all = await deviceRef().collection("events").where("type", "==", "HELPER_CHANGED").get();
    expect(all.docs.filter((d) => d.get("data.name") === "David" && d.get("data.change") === "added")).toHaveLength(1);
    const changes = await deviceRef().collection("changes").where("byUid", "==", david.uid).get();
    expect(changes.docs.map((d) => d.get("summary") as string)).toContain("Became one of your helpers");
  });

  it("declining deletes the pending member, and helpers are told", async () => {
    const emma = await helperClient("Emma");
    const { code: c2 } = await sarah.call<{ code: string }>("createHelperInvite", { familyId });
    await emma.call("acceptHelperInvite", { code: c2, displayName: "Emma" });
    await phone.call("approveHelper", { familyId, uid: emma.uid, approve: false });
    expect((await familyRef().collection("members").doc(emma.uid).get()).exists).toBe(false);
    expect((await familyRef().get()).get("memberUids")).not.toContain(emma.uid);
    const notice = await helperNotice("declined", "Emma");
    expect(notice.get("deliveredTo")).toEqual([sarah.uid, david.uid].sort());
  });
});

/** The delivered HELPER_CHANGED notice about `name`. */
function helperNotice(change: string, name: string) {
  return eventually(async () => {
    const snap = await deviceRef().collection("events").where("type", "==", "HELPER_CHANGED").get();
    const found = snap.docs.find((d) => d.get("data.change") === change && d.get("data.name") === name);
    return found && Array.isArray(found.get("deliveredTo")) ? found : undefined;
  });
}

describe("onConfigWritten", () => {
  it("a helper's edit writes a change entry the phone can show", async () => {
    await updateDoc(doc(sarah.db, configPath()), {
      "display.fontScale": 1.6,
      contacts: [{ id: "c_patel", name: "Dr. Patel", phone: "+1 555 010 3333", photoUrl: null, note: "" }],
      updatedBy: { uid: sarah.uid, name: "Sarah", kind: "helper" },
      updatedAt: serverTimestamp(),
    });
    const change = await eventually(async () => {
      const snap = await deviceRef().collection("changes").where("summary", "==", "Added Dr. Patel to contacts and changed text size").get();
      return snap.empty ? undefined : snap.docs[0]!.data();
    });
    expect(change).toMatchObject({ byUid: sarah.uid, byName: "Sarah" });
  });

  it("copies checkIn to the device doc", async () => {
    await updateDoc(doc(sarah.db, configPath()), {
      "checkIn.deadline": "10:00",
      updatedBy: { uid: sarah.uid, name: "Sarah", kind: "helper" },
      updatedAt: serverTimestamp(),
    });
    expect(
      await eventually(async () => {
        const d = (await deviceRef().get()).get("checkIn.deadline") as string;
        return d === "10:00" ? d : undefined;
      }),
    ).toBe("10:00");
  });

  it("the parent switching a protection off on the phone tells helpers", async () => {
    await setDoc(
      doc(phone.db, configPath()),
      {
        protection: { longCallAlert: { enabled: false, minutes: 20 } },
        updatedBy: { uid: phone.uid, name: "Margaret", kind: "device" },
        updatedAt: serverTimestamp(),
      },
      { merge: true },
    );
    const event = await eventually(async () => {
      const snap = await deviceRef().collection("events").where("type", "==", "PROTECTION_CHANGED_BY_PARENT").get();
      return snap.empty ? undefined : snap.docs[0]!;
    });
    expect(event.data()).toMatchObject({ severity: "NOTICE", source: "server", data: { feature: "longCall", enabled: "false" } });
    // ...and the new event is itself delivered.
    expect(await eventually(async () => (await event.ref.get()).get("deliveredTo") as string[] | undefined)).toEqual(
      [sarah.uid, david.uid].sort(),
    );
  });
});

describe("risky changes wait for the parent (SECURITY.md §3)", () => {
  it("David can't turn pause cards off directly; he asks, Margaret says yes on the phone, and everyone is told", async () => {
    const by = { updatedBy: { uid: david.uid, name: "David", kind: "helper" }, updatedAt: serverTimestamp() };
    await expect(updateDoc(doc(david.db, configPath()), { "protection.pauseTripwires.enabled": false, ...by })).rejects.toMatchObject({
      code: "permission-denied",
    });
    const request = await addDoc(collection(david.db, `families/${familyId}/devices/${deviceId}/requests`), {
      byUid: david.uid,
      byName: "David",
      changes: { pauseTripwiresEnabled: false },
      status: "pending",
      createdAt: serverTimestamp(),
    });
    // What the phone does on "Allow": write the config as the device, then answer.
    await setDoc(
      doc(phone.db, configPath()),
      {
        protection: { pauseTripwires: { enabled: false } },
        updatedBy: { uid: phone.uid, name: "Margaret", kind: "device" },
        updatedAt: serverTimestamp(),
      },
      { merge: true },
    );
    await updateDoc(doc(phone.db, request.path), { status: "approved", decidedAt: serverTimestamp() });

    const answered = deviceRef().collection("events").doc(`req_${request.id}`);
    const delivered = await eventually(async () => (await answered.get()).get("deliveredTo") as string[] | undefined);
    expect(delivered).toEqual([sarah.uid, david.uid].sort());
    expect((await answered.get()).data()).toMatchObject({
      type: "CHANGE_REQUEST_ANSWERED",
      severity: "URGENT",
      source: "server",
      data: { byName: "David", approved: "true", what: "turn off the pause before sending money" },
    });
    expect((await deviceRef().collection("changes").doc(`req_${request.id}`).get()).get("summary")).toBe(
      "Turned off the pause before sending money after you said yes",
    );
    expect((await admin().doc(configPath()).get()).get("protection.pauseTripwires.enabled")).toBe(false);
  });

  it("a declined request changes nothing and helpers are told", async () => {
    const request = await addDoc(collection(sarah.db, `families/${familyId}/devices/${deviceId}/requests`), {
      byUid: sarah.uid,
      byName: "Sarah",
      changes: { trustedPackages: ["com.anydesk.anydeskandroid"] },
      status: "pending",
      createdAt: serverTimestamp(),
    });
    await updateDoc(doc(phone.db, request.path), { status: "declined", decidedAt: serverTimestamp() });
    const answered = deviceRef().collection("events").doc(`req_${request.id}`);
    expect(await eventually(async () => (await answered.get()).get("deliveredTo") as string[] | undefined)).toEqual(
      [sarah.uid, david.uid].sort(),
    );
    expect((await answered.get()).get("data")).toMatchObject({ approved: "false", what: "trust a remote-control app" });
    expect((await deviceRef().collection("changes").doc(`req_${request.id}`).get()).exists).toBe(false);
  });
});

describe("code lockout and rate limiting", () => {
  it("5 failed tries lock a code", async () => {
    const { code } = await sarah.call<{ code: string }>("createHelperInvite", { familyId });
    const prober = await phoneClient();
    for (let i = 0; i < 5; i++) {
      expect(await errorReason(prober.call("claimDeviceInvite", { code }))).toBe("wrong-kind");
    }
    const late = await helperClient("Late");
    expect(await errorReason(late.call("acceptHelperInvite", { code, displayName: "Late" }))).toBe("locked");
  });

  it("a caller guessing codes is slowed down", async () => {
    const guesser = await phoneClient();
    const reasons: (string | undefined)[] = [];
    for (let i = 0; i < 11; i++) {
      reasons.push(await errorReason(guesser.call("previewInvite", { code: `ZZZZZ${"23456789"[i % 8]}` })));
    }
    expect(reasons.slice(0, 10).every((r) => r === "unknown-code")).toBe(true);
    expect(reasons[10]).toBe("rate-limited");
  });
});

describe("removeHelper", () => {
  it("a helper can't remove another helper unless they own the family", async () => {
    expect(await errorCode(david.call("removeHelper", { familyId, uid: sarah.uid }))).toBe("permission-denied");
  });

  it("the owner can't leave", async () => {
    expect(await errorReason(sarah.call("removeHelper", { familyId, uid: sarah.uid }))).toBe("owner");
  });

  it("the phone can remove the owner; ownership passes on", async () => {
    await phone.call("removeHelper", { familyId, uid: sarah.uid });
    const family = (await familyRef().get()).data()!;
    expect(family.memberUids).toEqual([david.uid]);
    expect(family.ownerUid).toBe(david.uid);
    expect((await familyRef().collection("members").doc(david.uid).get()).get("role")).toBe("owner");
    // David is told Sarah no longer helps, and the phone's Recent changes says so.
    const notice = await helperNotice("removed", "Sarah");
    expect(notice.get("deliveredTo")).toEqual([david.uid]);
    const changes = await deviceRef().collection("changes").where("byUid", "==", sarah.uid).get();
    expect(changes.docs.map((d) => d.get("summary") as string)).toContain("Is no longer one of your helpers");
  });

  it("but not the last helper", async () => {
    expect(await errorReason(phone.call("removeHelper", { familyId, uid: david.uid }))).toBe("last-helper");
  });
});

describe("checkInSweep", () => {
  it("creates one silent notice for a phone quiet for over a day, and is idempotent", async () => {
    const quietSince = Date.now() - 30 * 3600_000;
    await deviceRef().update({
      "status.lastHeartbeatAt": AdminTimestamp.fromMillis(quietSince),
      silentNotifiedAt: null,
      checkInNotifiedFor: null,
    });
    const first = await runCheckInSweep(admin(), new Date());
    expect(first.silent).toBeGreaterThanOrEqual(1);
    const second = await runCheckInSweep(admin(), new Date());
    expect(second.failed).toBe(0);

    const silent = await deviceRef().collection("events").doc(`silent_${quietSince}`).get();
    expect(silent.data()).toMatchObject({ type: "CHECK_IN_MISSED", severity: "URGENT", source: "server", data: { reason: "silent" } });
    const all = await deviceRef().collection("events").where("type", "==", "CHECK_IN_MISSED").get();
    expect(all.size).toBe(1);
    expect((await deviceRef().get()).get("silentNotifiedAt")).toBeInstanceOf(AdminTimestamp);
    expect(await eventually(async () => (await silent.ref.get()).get("deliveredTo") as string[] | undefined)).toEqual([david.uid]);
  });
});

describe("deletion", () => {
  it("a helper who isn't the owner can't delete the phone", async () => {
    const other = await helperClient("Other");
    expect(await errorCode(other.call("deleteDevice", { familyId, deviceId }))).toBe("not-found");
  });

  it("the phone deletes itself with its subcollections, storage and invites", async () => {
    const bucket = getStorage(getApp("test-admin")).bucket(`demo-guardian.appspot.com`);
    await bucket.file(`families/${familyId}/devices/${deviceId}/photos/p1.jpg`).save(Buffer.from([0xff, 0xd8]), {
      contentType: "image/jpeg",
    });
    await phone.call("deleteDevice", { familyId, deviceId });
    expect((await deviceRef().get()).exists).toBe(false);
    expect((await deviceRef().collection("events").limit(1).get()).empty).toBe(true);
    expect((await admin().doc(configPath()).get()).exists).toBe(false);
    expect((await familyRef().get()).get("deviceUids")).toEqual([]);
    expect((await admin().collection("invites").where("deviceId", "==", deviceId).get()).empty).toBe(true);
    const [files] = await bucket.getFiles({ prefix: `families/${familyId}/devices/${deviceId}/` });
    expect(files).toHaveLength(0);
  });

  it("only the owner deletes a family", async () => {
    const owner = await helperClient("Owner");
    const helper = await helperClient("Helper");
    const { familyId: f2 } = await owner.call<{ familyId: string }>("createFamily", { familyName: "F2", parentName: "Pat" });
    expect(await errorCode(helper.call("deleteFamily", { familyId: f2 }))).toBe("not-found");
    await owner.call("createHelperInvite", { familyId: f2 }).catch(() => undefined);
    await owner.call("deleteFamily", { familyId: f2 });
    expect((await admin().doc(`families/${f2}`).get()).exists).toBe(false);
    expect((await admin().collection(`families/${f2}/devices`).get()).empty).toBe(true);
  });
});

