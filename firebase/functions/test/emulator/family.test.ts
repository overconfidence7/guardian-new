/*
 * Track B (qa/antigravity/cases/13-family-linking-e2e.md and the [B] cases of 08, 12, 14
 * and 19) at the backend layer. The parent's phone is an anonymous web-SDK client making
 * the same callable calls and Firestore/Storage writes as android/.../cloud/CloudSync.kt;
 * helpers are email/password clients. Everything runs against the Emulator Suite, so the
 * real rules, callables and triggers are exercised.
 */
import { randomUUID } from "node:crypto";
import { FieldValue, Timestamp as AdminTimestamp } from "firebase-admin/firestore";
import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  onSnapshot,
  query,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  where,
  type DocumentData,
} from "firebase/firestore";
import { connectStorageEmulator, getBytes, getStorage, ref, uploadBytes, type FirebaseStorage } from "firebase/storage";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { FakeMessenger, type PushMessage } from "../../src/lib/messaging.js";
import { readDeviceConfig } from "../../src/lib/model.js";
import { runCheckInSweep } from "../../src/triggers/checkInSweep.js";
import { deliverEvent } from "../../src/triggers/onEventCreated.js";
import { admin, closeAll, errorCode, errorReason, eventually, helperClient, phoneClient, type Client } from "./harness.js";

const knownBug = it.skipIf(!process.env.INCLUDE_KNOWN_BUGS);

let sarah: Client; // creates the family (owner)
let phone: Client; // Mom's phone
let familyId: string;
let deviceId: string;
let deviceCode: string;

const familyRef = () => admin().doc(`families/${familyId}`);
const deviceRef = () => admin().doc(`families/${familyId}/devices/${deviceId}`);
const devicePath = (p = "") => `families/${familyId}/devices/${deviceId}${p}`;
const configPath = () => devicePath("/config/current");
const helperBy = (c: Client, name: string) => ({ updatedBy: { uid: c.uid, name, kind: "helper" }, updatedAt: serverTimestamp() });
const deviceBy = (c: Client) => ({ updatedBy: { uid: c.uid, name: "Mom", kind: "device" }, updatedAt: serverTimestamp() });

/** "ok", or the error code a client SDK call failed with ("permission-denied", "storage/unauthorized"...). */
async function outcome(p: Promise<unknown>): Promise<string> {
  try {
    await p;
    return "ok";
  } catch (error) {
    return (error as { code?: string }).code ?? String(error);
  }
}

const storages = new Map<string, FirebaseStorage>();
function storageOf(c: Client): FirebaseStorage {
  let s = storages.get(c.uid);
  if (!s) {
    s = getStorage(c.app, "gs://demo-guardian.appspot.com");
    connectStorageEmulator(s, "127.0.0.1", 9199);
    storages.set(c.uid, s);
  }
  return s;
}

const JPEG = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x10, 0x4a, 0x46, 0x49, 0x46]);

/** The event the phone uploads from its outbox (CloudSync.eventMap), with a client-chosen id. */
function phoneEvent(type: string, severity: string, data: Record<string, string>): DocumentData {
  return { type, severity, at: Date.now(), data, source: "device", createdAt: serverTimestamp() };
}

async function sendEvent(c: Client, event: DocumentData, id: string = randomUUID()): Promise<string> {
  await setDoc(doc(c.db, devicePath(`/events/${id}`)), event);
  return devicePath(`/events/${id}`);
}

const deliveredTo = (path: string) =>
  eventually(async () => (await admin().doc(path).get()).get("deliveredTo") as string[] | undefined);

/**
 * Waits for the onEventCreated trigger to finish, then runs the same delivery again with a
 * recording messenger to see exactly what helpers were sent (the emulator's own messenger
 * only logs).
 */
async function pushesFor(path: string): Promise<{ deliveredTo: string[]; sent: { tokens: string[]; message: PushMessage }[] }> {
  const delivered = await deliveredTo(path);
  await admin().doc(path).update({ deliveredTo: FieldValue.delete() });
  const segments = path.split("/");
  const fake = new FakeMessenger();
  await deliverEvent(admin(), fake, { familyId: segments[1]!, deviceId: segments[3]!, eventId: segments[5]! });
  return { deliveredTo: delivered, sent: fake.sent };
}

/** Failed code lookups count per IP too, and every test client is 127.0.0.1. */
async function clearRateLimits(): Promise<void> {
  const snap = await admin().collection("rateLimits").get();
  await Promise.all(snap.docs.map((d) => d.ref.delete()));
}

beforeAll(async () => {
  await clearRateLimits();
  [sarah, phone] = await Promise.all([helperClient("Sarah"), phoneClient()]);
  const out = await sarah.call<{ familyId: string; deviceId: string }>("createFamily", {
    familyName: "The Rivera family",
    parentName: "Mom",
    helperPhone: "+1 555 010 1111",
    timezone: "America/Chicago",
  });
  familyId = out.familyId;
  deviceId = out.deviceId;
  // Sarah's dashboard registers for web push (her own member doc, through the rules).
  await updateDoc(doc(sarah.db, `families/${familyId}/members/${sarah.uid}`), { pushTokens: ["tok-sarah"] });
});

afterAll(async () => {
  await clearRateLimits();
  await closeAll();
});

// ---------------------------------------------------------------------------------------

describe("pairing", () => {
  it("GM-FAM-001 a helper creates the family and gets a 15-minute, single-use device code", async () => {
    const family = (await familyRef().get()).data()!;
    expect(family).toMatchObject({ name: "The Rivera family", ownerUid: sarah.uid, memberUids: [sarah.uid], deviceUids: [] });
    expect((await familyRef().collection("members").doc(sarah.uid).get()).data()).toMatchObject({
      role: "owner",
      status: "active",
      displayName: "Sarah",
      phone: "+1 555 010 1111",
    });
    expect((await deviceRef().get()).data()).toMatchObject({ parentName: "Mom", deviceUid: null });

    const before = Date.now();
    const out = await sarah.call<{ code: string; expiresAt: number }>("createDeviceInvite", { familyId, deviceId });
    deviceCode = out.code;
    expect(deviceCode).toMatch(/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{6}$/);
    const invite = (await admin().doc(`invites/${deviceCode}`).get()).data()!;
    expect(invite).toMatchObject({ familyId, deviceId, kind: "device", usedBy: null, createdByName: "Sarah" });
    const ttl = (invite.expiresAt as AdminTimestamp).toMillis() - before;
    expect(ttl).toBeGreaterThan(14.5 * 60_000);
    expect(ttl).toBeLessThanOrEqual(15 * 60_000 + 5_000);
  });

  // web/src/data/firebaseApi.ts watchMyFamilies lists families where memberUids contains
  // the helper; firestore.rules guards /families/{familyId} with isHelper(familyId), a
  // get() the rules can't evaluate for a list query ("Null value error"), so every such
  // query is denied and Root.tsx sends a returning helper to "create a family".
  knownBug("GM-FAM-001 a returning helper can list the families they belong to (the dashboard's home query) [known bug DASH-LIST]", async () => {
    const mine = await getDocs(query(collection(sarah.db, "families"), where("memberUids", "array-contains", sarah.uid)));
    expect(mine.docs.map((d) => d.id)).toEqual([familyId]);
  });

  it("GM-FAM-001 a helper can't list families they don't belong to", async () => {
    const stranger = await helperClient("Stranger");
    expect(await outcome(getDocs(query(collection(stranger.db, "families"), where("memberUids", "array-contains", sarah.uid))))).toBe(
      "permission-denied",
    );
  });

  it("GM-FAM-005 previewing a code (then \"No thanks\") links nothing, and the code still works", async () => {
    const curious = await phoneClient();
    expect(await curious.call("previewInvite", { code: deviceCode })).toEqual({
      kind: "device",
      familyName: "The Rivera family",
      parentName: "Mom",
      helperName: "Sarah",
    });
    expect((await deviceRef().get()).get("deviceUid")).toBeNull();
    expect((await familyRef().get()).get("deviceUids")).toEqual([]);
    expect((await admin().doc(`invites/${deviceCode}`).get()).get("usedBy")).toBeNull();
    expect(await outcome(getDoc(doc(curious.db, devicePath())))).toBe("permission-denied");
  });

  it("GM-FAM-002 the phone joins with the code: the device records the phone and the code is used up", async () => {
    expect(await phone.call("previewInvite", { code: deviceCode.toLowerCase() })).toMatchObject({ helperName: "Sarah" });
    const out = await phone.call("claimDeviceInvite", { code: deviceCode, model: "Google sdk_gphone64", androidVersion: "16", appVersion: "0.4.0" });
    expect(out).toEqual({ familyId, deviceId });

    const device = (await deviceRef().get()).data()!;
    expect(device).toMatchObject({ deviceUid: phone.uid, model: "Google sdk_gphone64", androidVersion: "16", appVersion: "0.4.0" });
    expect((await admin().doc(`invites/${deviceCode}`).get()).get("usedBy")).toBe(phone.uid);
    expect((await familyRef().get()).get("deviceUids")).toEqual([phone.uid]);
    // The phone can now read its device and config; the dashboard sees it as connected.
    expect(await outcome(getDoc(doc(phone.db, configPath())))).toBe("ok");
    expect((await getDoc(doc(sarah.db, devicePath()))).get("deviceUid")).toBe(phone.uid);
  });

  it("GM-FAM-004 wrong, expired, reused and locked codes are refused; the same phone may retry its own claim", async () => {
    // A wrong code, counted against the caller.
    const typo = await phoneClient();
    expect(await errorReason(typo.call("previewInvite", { code: "ZZZZZZ" }))).toBe("unknown-code");
    expect(await errorReason(typo.call("claimDeviceInvite", { code: "ZZZZZZ" }))).toBe("unknown-code");
    // (A window boundary between the two tries would restart the count, hence >= 1.)
    expect((await admin().doc(`rateLimits/uid_${typo.uid}`).get()).get("failures")).toBeGreaterThanOrEqual(1);

    // A code already used by another phone, and the same phone retrying its claim.
    const other = await phoneClient();
    expect(await errorReason(other.call("claimDeviceInvite", { code: deviceCode }))).toBe("used");
    expect(await phone.call("claimDeviceInvite", { code: deviceCode })).toEqual({ familyId, deviceId });
    expect((await deviceRef().get()).get("deviceUid")).toBe(phone.uid);

    // An expired code.
    const { code: expired } = await sarah.call<{ code: string }>("createDeviceInvite", { familyId, deviceId });
    await admin().doc(`invites/${expired}`).update({ expiresAt: AdminTimestamp.fromMillis(Date.now() - 1000) });
    expect(await errorReason(other.call("previewInvite", { code: expired }))).toBe("expired");

    // Five failed tries against one real code lock it: the right use fails afterwards.
    const { code: target } = await sarah.call<{ code: string }>("createDeviceInvite", { familyId, deviceId });
    const prober = await helperClient("Prober");
    for (let i = 0; i < 5; i++) {
      expect(await errorReason(prober.call("acceptHelperInvite", { code: target, displayName: "Prober" }))).toBe("wrong-kind");
    }
    expect(await errorReason(other.call("previewInvite", { code: target }))).toBe("locked");
    expect((await deviceRef().get()).get("deviceUid")).toBe(phone.uid);
    await clearRateLimits();
  });
});

// ---------------------------------------------------------------------------------------

describe("live changes", () => {
  it("GM-FAM-010 a helper's change reaches the phone within 10 seconds and is listed as Sarah's change", async () => {
    const seen = new Promise<number>((resolve, reject) => {
      const off = onSnapshot(
        doc(phone.db, configPath()),
        (snap) => {
          if (snap.get("display.fontScale") === 1.6) {
            off();
            resolve(Date.now());
          }
        },
        reject,
      );
    });
    const start = Date.now();
    await updateDoc(doc(sarah.db, configPath()), {
      "display.fontScale": 1.6,
      "display.theme": "DARK",
      "display.highContrast": true,
      "home.columns": 3,
      "home.showPhotoCard": false,
      ...helperBy(sarah, "Sarah"),
    });
    expect((await seen) - start).toBeLessThan(10_000);

    const config = (await getDoc(doc(phone.db, configPath()))).data()!;
    expect(config).toMatchObject({ display: { fontScale: 1.6, theme: "DARK", highContrast: true }, home: { columns: 3, showPhotoCard: false } });

    const change = await eventually(async () => {
      const snap = await getDocs(collection(phone.db, devicePath("/changes")));
      return snap.docs.map((d) => d.data()).find((c) => String(c.summary).startsWith("Turned off the photo card"));
    });
    expect(change).toMatchObject({ byUid: sarah.uid, byName: "Sarah" });
    expect(change.summary).toBe("Turned off the photo card, changed the home screen to 3 columns and 3 other changes");
  });

  it("GM-FAM-010 the check-in time a helper sets is copied to the device doc for the sweep", async () => {
    await updateDoc(doc(sarah.db, configPath()), { "checkIn.deadline": "09:30", ...helperBy(sarah, "Sarah") });
    expect(await eventually(async () => ((await deviceRef().get()).get("checkIn.deadline") === "09:30" ? true : undefined))).toBe(true);
  });

  it("GM-FAM-012 the parent's change and a helper's change made at once both survive", async () => {
    await Promise.all([
      updateDoc(doc(phone.db, configPath()), { "protection.longCallAlert.enabled": false, ...deviceBy(phone) }),
      updateDoc(doc(sarah.db, configPath()), { "display.fontScale": 1.8, ...helperBy(sarah, "Sarah") }),
    ]);
    const config = await admin().doc(configPath()).get();
    expect(config.get("protection.longCallAlert.enabled")).toBe(false);
    expect(config.get("display.fontScale")).toBe(1.8);
    // Put it back for the tests below (as the parent).
    await updateDoc(doc(phone.db, configPath()), { "protection.longCallAlert.enabled": true, ...deviceBy(phone) });
  });

  it("GM-FAM-013 the parent switching off pause cards and the remote-control warning tells the family, once per feature", async () => {
    await updateDoc(doc(phone.db, configPath()), {
      "protection.pauseTripwires.enabled": false,
      "protection.remoteAccessAlarm.enabled": false,
      ...deviceBy(phone),
    });
    const events = await eventually(async () => {
      const snap = await deviceRef().collection("events").where("type", "==", "PROTECTION_CHANGED_BY_PARENT").get();
      const features = snap.docs.filter((d) => ["pause", "remoteAccess"].includes(d.get("data.feature") as string));
      return features.length === 2 ? features : undefined;
    });
    for (const e of events) {
      expect(e.data()).toMatchObject({ severity: "NOTICE", source: "server", data: { enabled: "false" } });
    }
    const pause = events.find((e) => e.get("data.feature") === "pause")!;
    const { deliveredTo: to, sent } = await pushesFor(pause.ref.path);
    expect(to).toEqual([sarah.uid]);
    expect(sent[0]!.message.notification).toEqual({
      title: "Mom turned off the pause before sending money",
      body: "Mom turned off the pause before sending money on their phone. That’s up to them. You could ask how it’s going next time you talk.",
    });
    // Sarah reads the event on the dashboard; the phone can't read events.
    expect(await outcome(getDoc(doc(sarah.db, pause.ref.path)))).toBe("ok");
    expect(await outcome(getDoc(doc(phone.db, pause.ref.path)))).toBe("permission-denied");
    await updateDoc(doc(phone.db, configPath()), {
      "protection.pauseTripwires.enabled": true,
      "protection.remoteAccessAlarm.enabled": true,
      ...deviceBy(phone),
    });
  });

  it("GM-FAM-014 a pause reported by the phone is accepted and reaches helpers as \"paused Guardian Mode\" (backend part of S-04)", async () => {
    const path = await sendEvent(phone, phoneEvent("PROTECTION_CHANGED_BY_PARENT", "NOTICE", { feature: "all", enabled: "false" }));
    const { deliveredTo: to, sent } = await pushesFor(path);
    expect(to).toEqual([sarah.uid]);
    expect(sent[0]!.message.notification?.title).toBe("Mom paused Guardian Mode");
  });

  it("GM-FAM-042 protection settings from the dashboard reach the phone; the change log never repeats the safe word", async () => {
    // The phone has reported its apps, so the dashboard can name Hometown CU.
    await updateDoc(doc(phone.db, devicePath()), {
      installedApps: [
        { packageName: "org.hometowncu.mobile", label: "Hometown CU" },
        { packageName: "com.anydesk.anydeskandroid", label: "AnyDesk" },
      ],
    });
    await updateDoc(doc(sarah.db, configPath()), {
      "protection.pauseTripwires.windowMinutes": 10,
      "protection.longCallAlert.minutes": 10,
      "protection.pauseTripwires.extraPackages": { "org.hometowncu.mobile": "BANK" },
      ...helperBy(sarah, "Sarah"),
    });
    const summary = "Changed the pause to last 10 minutes after a call, added Hometown CU to apps that get a pause card and changed long-call notices to 10 minutes";
    expect(
      await eventually(async () => {
        const snap = await deviceRef().collection("changes").where("summary", "==", summary).get();
        return snap.empty ? undefined : snap.docs[0]!.get("byName") as string;
      }),
    ).toBe("Sarah");

    await updateDoc(doc(sarah.db, configPath()), { "protection.safeWord.word": "bluebird", ...helperBy(sarah, "Sarah") });
    expect(
      await eventually(async () => {
        const snap = await deviceRef().collection("changes").where("summary", "==", "Set the family safe word").get();
        return snap.empty ? undefined : true;
      }),
    ).toBe(true);
    const all = await getDocs(collection(phone.db, devicePath("/changes")));
    expect(all.docs.some((d) => JSON.stringify(d.data()).includes("bluebird"))).toBe(false);

    const protection = (await getDoc(doc(phone.db, configPath()))).get("protection") as DocumentData;
    expect(protection).toMatchObject({
      pauseTripwires: { windowMinutes: 10, extraPackages: { "org.hometowncu.mobile": "BANK" } },
      longCallAlert: { minutes: 10 },
      safeWord: { word: "bluebird" },
    });
  });

  it("GM-SCRN-007 voicemail mode set from the dashboard is stored, read by the phone and listed as a change", async () => {
    await updateDoc(doc(sarah.db, configPath()), { "protection.callScreening.mode": "VOICEMAIL_UNKNOWN", ...helperBy(sarah, "Sarah") });
    expect((await getDoc(doc(phone.db, configPath()))).get("protection.callScreening.mode")).toBe("VOICEMAIL_UNKNOWN");
    // The server's lenient schema (same rules as the phone's decoder) keeps it, not the default.
    expect(readDeviceConfig((await admin().doc(configPath()).get()).data())?.protection.callScreening.mode).toBe("VOICEMAIL_UNKNOWN");
    expect(
      await eventually(async () => {
        const snap = await deviceRef().collection("changes").where("summary", "==", "Set unknown callers to go to voicemail").get();
        return snap.empty ? undefined : snap.docs[0]!.get("byName") as string;
      }),
    ).toBe("Sarah");
  });

  it("GM-FAM-045 junk config from a helper: oversized writes are refused; odd values don't stop later changes reaching the phone", async () => {
    // Over Firestore's 1 MiB document limit (the case's 10 MB string), refused outright.
    expect(await outcome(updateDoc(doc(sarah.db, configPath()), { "display.note": "x".repeat(2 * 1024 * 1024), ...helperBy(sarah, "Sarah") }))).not.toBe("ok");

    // What the rules accept today (recorded): an unknown field, a bad enum, a negative window.
    expect(
      await outcome(
        updateDoc(doc(sarah.db, configPath()), {
          hacked: true,
          "display.theme": "NEON",
          "protection.pauseTripwires.windowMinutes": -5,
          ...helperBy(sarah, "Sarah"),
        }),
      ),
    ).toBe("ok");
    // The server reads it leniently, like the phone: unknown enum values fall back to the default.
    expect(readDeviceConfig((await admin().doc(configPath()).get()).data())?.display.theme).toBe("LIGHT");

    // The pipeline isn't stuck: the next ordinary change is still logged for the phone.
    await updateDoc(doc(sarah.db, configPath()), {
      "display.theme": "DARK",
      "protection.pauseTripwires.windowMinutes": 30,
      "display.fontScale": 2,
      ...helperBy(sarah, "Sarah"),
    });
    const summary = "Changed text size, changed the colors to dark and changed the pause to last 30 minutes after a call";
    expect(
      await eventually(async () => {
        const snap = await getDocs(collection(phone.db, devicePath("/changes")));
        return snap.docs.some((d) => d.get("summary") === summary) ? true : undefined;
      }),
    ).toBe(true);
    expect((await getDoc(doc(phone.db, configPath()))).get("display.fontScale")).toBe(2);
  });
});

// ---------------------------------------------------------------------------------------

describe("helpers", () => {
  let david: Client;
  let emma: Client;

  beforeAll(async () => {
    // The family plan allows up to 4 helpers and 2 phones (plans aren't sold yet).
    await familyRef().update({ plan: "family" });
    [david, emma] = await Promise.all([helperClient("David"), helperClient("Emma")]);
  });

  it("GM-FAM-020 accepting an invite makes David a pending helper outside memberUids; the phone sees the request", async () => {
    const { code } = await sarah.call<{ code: string }>("createHelperInvite", { familyId });
    expect(await david.call("acceptHelperInvite", { code, displayName: "David", phone: "+1 555 010 2222" })).toEqual({
      familyId,
      status: "pending",
    });
    expect((await familyRef().get()).get("memberUids")).toEqual([sarah.uid]);
    // The phone's "David would like to be one of your helpers." card reads the member doc.
    const request = await getDoc(doc(phone.db, `families/${familyId}/members/${david.uid}`));
    expect(request.data()).toMatchObject({ status: "pending", displayName: "David", role: "helper" });
  });

  it("GM-FAM-021 GM-SCAM-007 a pending helper is denied every read and write until the parent says yes", async () => {
    const d = david.db;
    const results = {
      readFamily: await outcome(getDoc(doc(d, `families/${familyId}`))),
      listMembers: await outcome(getDocs(collection(d, `families/${familyId}/members`))),
      readDevice: await outcome(getDoc(doc(d, devicePath()))),
      listDevices: await outcome(getDocs(collection(d, `families/${familyId}/devices`))),
      readConfig: await outcome(getDoc(doc(d, configPath()))),
      writeConfig: await outcome(updateDoc(doc(d, configPath()), { "display.fontScale": 1, ...helperBy(david, "David") })),
      readEvents: await outcome(getDocs(collection(d, devicePath("/events")))),
      writeEvent: await outcome(addDoc(collection(d, devicePath("/events")), phoneEvent("HELP_REQUESTED", "URGENT", {}))),
      readPhotos: await outcome(getDocs(collection(d, devicePath("/photos")))),
      readChanges: await outcome(getDocs(collection(d, devicePath("/changes")))),
      activateSelf: await outcome(updateDoc(doc(d, `families/${familyId}/members/${david.uid}`), { status: "active" })),
      renameDevice: await outcome(updateDoc(doc(d, devicePath()), { parentName: "Grandma" })),
      uploadPhoto: await outcome(uploadBytes(ref(storageOf(david), devicePath("/photos/sneaky.jpg")), JPEG, { contentType: "image/jpeg" })),
    };
    expect(results).toEqual({
      readFamily: "permission-denied",
      listMembers: "permission-denied",
      readDevice: "permission-denied",
      listDevices: "permission-denied",
      readConfig: "permission-denied",
      writeConfig: "permission-denied",
      readEvents: "permission-denied",
      writeEvent: "permission-denied",
      readPhotos: "permission-denied",
      readChanges: "permission-denied",
      activateSelf: "permission-denied",
      renameDevice: "permission-denied",
      uploadPhoto: "storage/unauthorized",
    });
    // Callables treat a pending helper as outside the family.
    expect(await errorCode(david.call("createDeviceInvite", { familyId, deviceId }))).toBe("not-found");
    expect(await errorCode(david.call("createHelperInvite", { familyId }))).toBe("not-found");
    expect(await errorCode(david.call("approveHelper", { familyId, uid: david.uid, approve: true }))).toBe("permission-denied");
  });

  it("GM-FAM-020 a pending helper gets no pushes", async () => {
    await familyRef().collection("members").doc(david.uid).update({ pushTokens: ["tok-david"] });
    const path = await sendEvent(phone, phoneEvent("LONG_UNKNOWN_CALL", "URGENT", { minutes: "20", ongoing: "true" }));
    const { deliveredTo: to, sent } = await pushesFor(path);
    expect(to).toEqual([sarah.uid]);
    expect(sent.flatMap((s) => s.tokens)).toEqual(["tok-sarah"]);
  });

  it("GM-FAM-020 the parent's yes makes David an active helper who can see the phone and is added to its family list", async () => {
    await phone.call("approveHelper", { familyId, uid: david.uid, approve: true });
    expect((await familyRef().get()).get("memberUids")).toEqual([sarah.uid, david.uid]);
    expect((await familyRef().collection("members").doc(david.uid).get()).get("status")).toBe("active");
    expect(await outcome(getDoc(doc(david.db, devicePath())))).toBe("ok");
    expect(await outcome(getDocs(collection(david.db, devicePath("/events"))))).toBe("ok");
    const family = (await getDoc(doc(phone.db, configPath()))).get("family") as { name: string; phone: string }[];
    expect(family.map((f) => [f.name, f.phone])).toEqual([
      ["Sarah", "+1 555 010 1111"],
      ["David", "+1 555 010 2222"],
    ]);
  });

  it("GM-FAM-020 the parent's no removes the request; Emma never gets access", async () => {
    const { code } = await sarah.call<{ code: string }>("createHelperInvite", { familyId });
    await emma.call("acceptHelperInvite", { code, displayName: "Emma" });
    await phone.call("approveHelper", { familyId, uid: emma.uid, approve: false });
    expect((await familyRef().collection("members").doc(emma.uid).get()).exists).toBe(false);
    expect(await outcome(getDoc(doc(emma.db, devicePath())))).toBe("permission-denied");
  });

  it("GM-FAM-022 the parent removes David: his access ends at once", async () => {
    await phone.call("removeHelper", { familyId, uid: david.uid });
    expect((await familyRef().get()).get("memberUids")).toEqual([sarah.uid]);
    expect((await familyRef().collection("members").doc(david.uid).get()).exists).toBe(false);
    expect(await outcome(getDoc(doc(david.db, devicePath())))).toBe("permission-denied");
    expect(await outcome(getDocs(collection(david.db, devicePath("/events"))))).toBe("permission-denied");
    expect(await outcome(updateDoc(doc(david.db, configPath()), { "display.fontScale": 1, ...helperBy(david, "David") }))).toBe(
      "permission-denied",
    );
  });

  // docs/SECURITY.md §3 and §6: adding or removing a helper is announced to every helper.
  // approveHelper and removeHelper write no event and send no push today.
  knownBug("GM-FAM-020 GM-FAM-022 Sarah is told when a helper is approved, declined or removed [known bug SEC-3 helper notice]", async () => {
    const frank = await helperClient("Frank");
    const { code } = await sarah.call<{ code: string }>("createHelperInvite", { familyId });
    await frank.call("acceptHelperInvite", { code, displayName: "Frank" });
    const before = new Set((await deviceRef().collection("events").get()).docs.map((d) => d.id));
    const noticeForSarah = () =>
      eventually(async () => {
        const snap = await deviceRef().collection("events").get();
        const fresh = snap.docs.filter((d) => !before.has(d.id) && (d.get("deliveredTo") as string[] | undefined)?.includes(sarah.uid));
        return fresh.length > 0 ? fresh : undefined;
      }, 10_000);

    await phone.call("approveHelper", { familyId, uid: frank.uid, approve: true });
    expect((await noticeForSarah()).length).toBeGreaterThan(0);
    await phone.call("removeHelper", { familyId, uid: frank.uid });
    (await noticeForSarah()).forEach((d) => before.add(d.id));
  });

  it("GM-FAM-023 the family plan stops at 4 helpers and 2 phones", async () => {
    const owner = await helperClient("Owner");
    const { familyId: f } = await owner.call<{ familyId: string }>("createFamily", { familyName: "Plan test", parentName: "Pat" });
    await admin().doc(`families/${f}`).update({ plan: "family" });
    for (const name of ["H1", "H2", "H3"]) {
      const h = await helperClient(name);
      const { code } = await owner.call<{ code: string }>("createHelperInvite", { familyId: f });
      await h.call("acceptHelperInvite", { code, displayName: name });
    }
    // 1 owner + 3 pending = 4: a fifth invite is refused.
    expect(await errorReason(owner.call("createHelperInvite", { familyId: f }))).toBe("plan-limit");
    expect(await errorReason(owner.call("addDevice", { familyId: f, parentName: "Walter" }))).toBe(undefined);
    expect(await errorReason(owner.call("addDevice", { familyId: f, parentName: "Third" }))).toBe("plan-limit");
    await owner.call("deleteFamily", { familyId: f });
  });
});

// ---------------------------------------------------------------------------------------

describe("photos from family", () => {
  it("GM-FAM-030 Sarah's photo reaches the phone with its caption; removing it removes it from the phone", async () => {
    const photoId = `p_${randomUUID()}`;
    const storagePath = devicePath(`/photos/${photoId}.jpg`);
    await uploadBytes(ref(storageOf(sarah), storagePath), JPEG, { contentType: "image/jpeg" });

    const photos: DocumentData[][] = [];
    const off = onSnapshot(collection(phone.db, devicePath("/photos")), (snap) => photos.push(snap.docs.map((d) => d.data())));
    await setDoc(doc(sarah.db, devicePath(`/photos/${photoId}`)), {
      storagePath,
      fromUid: sarah.uid,
      fromName: "Sarah",
      caption: "Emma's first day of school",
      createdAt: serverTimestamp(),
    });
    const shown = await eventually(async () => {
      await Promise.resolve();
      return photos.at(-1)?.find((p) => p.storagePath === storagePath);
    }, 10_000);
    expect(shown).toMatchObject({ fromName: "Sarah", caption: "Emma's first day of school" });
    expect(await outcome(getBytes(ref(storageOf(phone), storagePath)))).toBe("ok");

    await deleteDoc(doc(sarah.db, devicePath(`/photos/${photoId}`)));
    expect(
      await eventually(async () => {
        await Promise.resolve();
        const latest = photos.at(-1);
        return latest && !latest.some((p) => p.storagePath === storagePath) ? true : undefined;
      }, 10_000),
    ).toBe(true);
    off();
  });

  it("GM-FAM-031 a 200-character caption with emoji and accents is accepted; 201 characters are refused", async () => {
    const base = "Émilie’s café 🎂 — ";
    const caption200 = (base + "a".repeat(200)).slice(0, 200 - 1) + "!";
    expect(caption200).toHaveLength(200);
    const photo = (caption: string) => {
      const id = `p_${randomUUID()}`;
      return setDoc(doc(sarah.db, devicePath(`/photos/${id}`)), {
        storagePath: devicePath(`/photos/${id}.jpg`),
        fromUid: sarah.uid,
        fromName: "Sarah",
        caption,
        createdAt: serverTimestamp(),
      });
    };
    expect(await outcome(photo(caption200))).toBe("ok");
    expect(await outcome(photo("é".repeat(200)))).toBe("ok");
    expect(await outcome(photo("a".repeat(201)))).toBe("permission-denied");
  });
});

// ---------------------------------------------------------------------------------------

describe("events from the phone", () => {
  const cases: { type: string; severity: string; data: Record<string, string>; title: string }[] = [
    { type: "PAUSE_SHOWN", severity: "NOTICE", data: { app: "Chase", category: "BANK", duringCall: "false", minutesSinceCall: "4" }, title: "Mom paused before opening Chase" },
    { type: "PAUSE_SHOWN", severity: "URGENT", data: { app: "Chase", category: "BANK", duringCall: "true", minutesSinceCall: "0" }, title: "Mom paused before opening Chase" },
    { type: "PAUSE_CONTINUED", severity: "NOTICE", data: { app: "Chase" }, title: "Mom went ahead with Chase" },
    { type: "PAUSE_CALLED_FAMILY", severity: "NOTICE", data: { app: "Chase" }, title: "Mom chose to call family first" },
    { type: "REMOTE_ACCESS_INSTALLED", severity: "URGENT", data: { app: "AnyDesk", afterUnknownCall: "true" }, title: "AnyDesk was installed on Mom’s phone" },
    { type: "REMOTE_ACCESS_OPENED", severity: "URGENT", data: { app: "AnyDesk", afterUnknownCall: "true" }, title: "Mom opened AnyDesk" },
    { type: "CONTROL_APP_INSTALLED", severity: "NOTICE", data: { app: "Screen Helper", afterUnknownCall: "false" }, title: "A new app can control Mom’s screen" },
    { type: "LONG_UNKNOWN_CALL", severity: "NOTICE", data: { minutes: "20", ongoing: "true" }, title: "Mom is on a long call" },
    { type: "HELP_REQUESTED", severity: "URGENT", data: { note: "Is this real?" }, title: "Mom asked for help" },
    { type: "REMINDER_MISSED", severity: "NOTICE", data: { title: "Blood pressure pill", time: "08:00" }, title: "Mom hasn’t confirmed a reminder" },
    { type: "PROTECTION_CHANGED_BY_PARENT", severity: "NOTICE", data: { feature: "longCall", enabled: "false" }, title: "Mom turned off long-call notices" },
  ];

  it("GM-FAM-040 every event the phone sends is stored, delivered to helpers with their wording, urgent ones marked urgent", async () => {
    for (const c of cases) {
      const path = await sendEvent(phone, phoneEvent(c.type, c.severity, c.data));
      const { deliveredTo: to, sent } = await pushesFor(path);
      expect(to, c.type).toEqual([sarah.uid]);
      expect(sent, c.type).toHaveLength(1);
      expect(sent[0]!.message.notification?.title, c.type).toBe(c.title);
      expect(sent[0]!.message.highPriority, c.type).toBe(c.severity === "URGENT");
      expect(sent[0]!.message.data, c.type).toMatchObject({ type: "event", eventType: c.type, severity: c.severity, familyId, deviceId });
      expect((await admin().doc(path).get()).get("expireAt"), c.type).toBeInstanceOf(AdminTimestamp);
    }
    // The exact words are checked against SPEC F4 in test/unit/wording.test.ts (GM-COPY-002);
    // here it's enough that the push names the parent and the app.
    const pause = await sendEvent(phone, phoneEvent("PAUSE_SHOWN", "NOTICE", cases[0]!.data));
    expect((await pushesFor(pause)).sent[0]!.message.notification?.body).toMatch(/^Mom opened Chase 4 minutes after a call from an unknown number/);
  });

  it("GM-FAM-040 PERMISSION_MISSING is recorded for the dashboard but not pushed", async () => {
    const path = await sendEvent(phone, phoneEvent("PERMISSION_MISSING", "INFO", { permission: "usageAccess" }));
    const { deliveredTo: to, sent } = await pushesFor(path);
    expect(to).toEqual([]);
    expect(sent).toEqual([]);
    expect(await outcome(getDoc(doc(sarah.db, path)))).toBe("ok");
  });

  it("GM-FAM-041 an event re-sent after reconnecting (same id) is refused, so it is stored and pushed once", async () => {
    const id = randomUUID();
    const event = phoneEvent("PAUSE_CONTINUED", "NOTICE", { app: "Venmo" });
    const path = await sendEvent(phone, event, id);
    await deliveredTo(path);
    // CloudSync.flushOutbox drops an event the server refuses as a duplicate.
    expect(await outcome(setDoc(doc(phone.db, path), event))).toBe("permission-denied");
    const same = await deviceRef().collection("events").where("type", "==", "PAUSE_CONTINUED").where("data.app", "==", "Venmo").get();
    expect(same.size).toBe(1);
    // A retried trigger doesn't push twice either.
    const fake = new FakeMessenger();
    expect(await deliverEvent(admin(), fake, { familyId, deviceId, eventId: id })).toMatchObject({ skipped: "already-delivered" });
    expect(fake.sent).toEqual([]);
  });

  it("GM-FAM-041 three events queued offline arrive in order, once each", async () => {
    const ids: string[] = [randomUUID(), randomUUID(), randomUUID()];
    const base = Date.now() - 5 * 60_000;
    for (const [i, id] of ids.entries()) {
      await sendEvent(phone, { ...phoneEvent("PAUSE_CALLED_FAMILY", "NOTICE", { app: `Bank ${i}` }), at: base + i * 1000 }, id);
    }
    for (const id of ids) await deliveredTo(devicePath(`/events/${id}`));
    const snap = await getDocs(collection(sarah.db, devicePath("/events")));
    const mine = snap.docs.filter((d) => ids.includes(d.id)).sort((a, b) => (a.get("at") as number) - (b.get("at") as number));
    expect(mine.map((d) => d.get("data.app") as string)).toEqual(["Bank 0", "Bank 1", "Bank 2"]);
  });

  it("GM-FAM-046 the phone's own sign-in can't send arbitrary events, write another family, or read events", async () => {
    const strangerOwner = await helperClient("Stranger");
    const { familyId: otherFamily, deviceId: otherDevice } = await strangerOwner.call<{ familyId: string; deviceId: string }>("createFamily", {
      familyName: "Others",
      parentName: "Pat",
    });
    const ok = phoneEvent("PAUSE_SHOWN", "NOTICE", { app: "Chase" });
    const send = (e: DocumentData) => outcome(sendEvent(phone, e));
    const results = {
      control: await send(ok),
      unknownType: await send({ ...ok, type: "SMS_READ" }),
      serverOnlyType: await send({ ...ok, type: "CHECK_IN_MISSED" }),
      phoneNumberField: await send({ ...ok, number: "+15550109999" }),
      messageField: await send({ ...ok, message: "Your code is 123456" }),
      hugeData: await send({ ...ok, data: Object.fromEntries(Array.from({ length: 50 }, (_, i) => [`k${i}`, "v"])) }),
      longValue: await send({ ...ok, data: { app: "x".repeat(5000) } }),
      nestedData: await send({ ...ok, data: { app: { name: "Chase" } } }),
      serverSource: await send({ ...ok, source: "server" }),
      backdated: await send({ ...ok, createdAt: Timestamp.fromMillis(0) }),
      selfDelivered: await send({ ...ok, deliveredTo: [] }),
      otherFamilyEvent: await outcome(addDoc(collection(phone.db, `families/${otherFamily}/devices/${otherDevice}/events`), ok)),
      otherFamilyDevice: await outcome(updateDoc(doc(phone.db, `families/${otherFamily}/devices/${otherDevice}`), { fcmToken: "t" })),
      otherFamilyConfig: await outcome(getDoc(doc(phone.db, `families/${otherFamily}/devices/${otherDevice}/config/current`))),
      readEvents: await outcome(getDocs(collection(phone.db, devicePath("/events")))),
    };
    expect(results).toEqual({
      control: "ok",
      unknownType: "permission-denied",
      serverOnlyType: "permission-denied",
      phoneNumberField: "permission-denied",
      messageField: "permission-denied",
      hugeData: "permission-denied",
      longValue: "permission-denied",
      nestedData: "permission-denied",
      serverSource: "permission-denied",
      backdated: "permission-denied",
      selfDelivered: "permission-denied",
      otherFamilyEvent: "permission-denied",
      otherFamilyDevice: "permission-denied",
      otherFamilyConfig: "permission-denied",
      readEvents: "permission-denied",
    });
    // A fresh anonymous sign-in that never claimed a code can't write as this phone.
    const unpaired = await phoneClient();
    expect(await outcome(sendEvent(unpaired, ok))).toBe("permission-denied");
    await strangerOwner.call("deleteFamily", { familyId: otherFamily });
  });
});

// ---------------------------------------------------------------------------------------

describe("Help screenshots", () => {
  it("GM-HELP-006 the phone uploads one JPEG and a HELP_REQUESTED event with the note and path; helpers see both", async () => {
    const id = randomUUID();
    const path = devicePath(`/help/${id}.jpg`);
    // CloudSync.uploadHelpScreenshot, then the HELP_REQUESTED event HelpActivity builds.
    expect(await outcome(uploadBytes(ref(storageOf(phone), path), JPEG, { contentType: "image/jpeg" }))).toBe("ok");
    const eventPath = await sendEvent(phone, phoneEvent("HELP_REQUESTED", "URGENT", { path, note: "Is this real?" }), id);
    const { deliveredTo: to, sent } = await pushesFor(eventPath);
    expect(to).toEqual([sarah.uid]);
    expect(sent[0]!.message.notification).toEqual({
      title: "Mom asked for help",
      body: "Mom sent you a picture of their screen with a note: “Is this real?”",
    });
    expect(sent[0]!.message.highPriority).toBe(true);
    const event = (await getDoc(doc(sarah.db, eventPath))).data()!;
    expect(event.data).toEqual({ path, note: "Is this real?" });
    expect(await outcome(getBytes(ref(storageOf(sarah), path)))).toBe("ok");
  });

  it("GM-HELP-003 GM-HELP-008 only the paired phone uploads, only JPEG up to 5 MB; the phone can't read it back", async () => {
    const id = randomUUID();
    const path = devicePath(`/help/${id}.jpg`);
    const up = (c: Client, p: string, bytes: Uint8Array, type: string) => outcome(uploadBytes(ref(storageOf(c), p), bytes, { contentType: type }));
    const stranger = await phoneClient();
    const results = {
      png: await up(phone, path, JPEG, "image/png"),
      tooBig: await up(phone, path, new Uint8Array(5 * 1024 * 1024 + 1), "image/jpeg"),
      wrongName: await up(phone, devicePath(`/help/${id}.png`), JPEG, "image/jpeg"),
      unpairedPhone: await up(stranger, path, JPEG, "image/jpeg"),
      helperUpload: await up(sarah, path, JPEG, "image/jpeg"),
      atLimit: await up(phone, path, new Uint8Array(5 * 1024 * 1024), "image/jpeg"),
      phoneReadsBack: await outcome(getBytes(ref(storageOf(phone), path))),
      strangerReads: await outcome(getBytes(ref(storageOf(stranger), path))),
    };
    expect(results).toEqual({
      png: "storage/unauthorized",
      tooBig: "storage/unauthorized",
      wrongName: "storage/unauthorized",
      unpairedPhone: "storage/unauthorized",
      helperUpload: "storage/unauthorized",
      atLimit: "ok",
      phoneReadsBack: "storage/unauthorized",
      strangerReads: "storage/unauthorized",
    });
  });
});

// ---------------------------------------------------------------------------------------

describe("what the phone may write (privacy)", () => {
  it("GM-PRIV-002 the writes CloudSync makes are accepted: heartbeat, status, installed apps, consent, push token", async () => {
    const d = doc(phone.db, devicePath());
    const results = {
      heartbeat: await outcome(setDoc(d, { status: { lastHeartbeatAt: serverTimestamp(), lastSeenAt: serverTimestamp() } }, { merge: true })),
      status: await outcome(
        setDoc(
          d,
          {
            status: { protected: true, missing: ["overlay"], lastSeenAt: serverTimestamp() },
            installedApps: [{ packageName: "com.chase.sig.android", label: "Chase" }],
            appVersion: "0.4.0",
            model: "Google Pixel 7",
            androidVersion: "16",
          },
          { merge: true },
        ),
      ),
      consent: await outcome(setDoc(d, { consent: { version: 1, acceptedAt: serverTimestamp(), features: { pause: true, longCall: false } } }, { merge: true })),
      pushToken: await outcome(setDoc(d, { fcmToken: "fcm-token-1" }, { merge: true })),
    };
    expect(results).toEqual({ heartbeat: "ok", status: "ok", consent: "ok", pushToken: "ok" });
    const stored = (await deviceRef().get()).data()!;
    expect(Object.keys(stored.status as object).sort()).toEqual(["lastHeartbeatAt", "lastSeenAt", "missing", "protected"]);
  });

  it("GM-PRIV-002 GM-PRIV-020 nothing else about the parent is accepted: no numbers, messages, usage history or location", async () => {
    const d = doc(phone.db, devicePath());
    const results = {
      lastCaller: await outcome(setDoc(d, { status: { lastCaller: "+15550109999" } }, { merge: true })),
      foregroundApp: await outcome(setDoc(d, { status: { foregroundApp: "com.chase.sig.android" } }, { merge: true })),
      location: await outcome(setDoc(d, { location: { lat: 41.8, lng: -87.6 } }, { merge: true })),
      callLog: await outcome(setDoc(d, { callLog: [{ number: "+15550109999", minutes: 34 }] }, { merge: true })),
      parentName: await outcome(setDoc(d, { parentName: "Margaret Rivera" }, { merge: true })),
      checkIn: await outcome(setDoc(d, { checkIn: { enabled: false } }, { merge: true })),
      callsCollection: await outcome(setDoc(doc(phone.db, devicePath("/calls/c1")), { number: "+15550109999" })),
      messagesCollection: await outcome(setDoc(doc(phone.db, devicePath("/messages/m1")), { text: "hi" })),
      usageCollection: await outcome(setDoc(doc(phone.db, devicePath("/usage/u1")), { app: "Chase", minutes: 3 })),
      familyLevel: await outcome(setDoc(doc(phone.db, `families/${familyId}/location/now`), { lat: 1 })),
      topLevel: await outcome(setDoc(doc(phone.db, `uploads/${phone.uid}`), { anything: true })),
      changesLog: await outcome(setDoc(doc(phone.db, devicePath("/changes/fake")), { summary: "Sarah turned everything off" })),
      helperPhoto: await outcome(setDoc(doc(phone.db, devicePath("/photos/fake")), { fromName: "Sarah" })),
    };
    expect(results).toEqual(Object.fromEntries(Object.keys(results).map((k) => [k, "permission-denied"])));
  });

  it("GM-PRIV-003 the installed-apps list is shared with every active helper, and with nobody else", async () => {
    await setDoc(doc(phone.db, devicePath()), { installedApps: [{ packageName: "com.example.health", label: "Health Diary" }] }, { merge: true });
    const pending = await helperClient("Pending");
    const { code } = await sarah.call<{ code: string }>("createHelperInvite", { familyId });
    await pending.call("acceptHelperInvite", { code, displayName: "Pending" });
    const stranger = await helperClient("Nobody");
    expect((await getDoc(doc(sarah.db, devicePath()))).get("installedApps")).toEqual([{ packageName: "com.example.health", label: "Health Diary" }]);
    expect(await outcome(getDoc(doc(pending.db, devicePath())))).toBe("permission-denied");
    expect(await outcome(getDoc(doc(stranger.db, devicePath())))).toBe("permission-denied");
    await pending.call("removeHelper", { familyId, uid: pending.uid });
  });
});

// ---------------------------------------------------------------------------------------

describe("check-in", () => {
  it("GM-FAM-050 a heartbeat stores only timestamps", async () => {
    await setDoc(doc(phone.db, devicePath()), { status: { lastHeartbeatAt: serverTimestamp(), lastSeenAt: serverTimestamp() } }, { merge: true });
    const status = (await deviceRef().get()).get("status") as Record<string, unknown>;
    expect(status.lastHeartbeatAt).toBeInstanceOf(AdminTimestamp);
    expect(Math.abs((status.lastHeartbeatAt as AdminTimestamp).toMillis() - Date.now())).toBeLessThan(60_000);
    expect(
      await outcome(setDoc(doc(phone.db, devicePath()), { status: { lastHeartbeatAt: serverTimestamp(), unlockedFrom: "Chase" } }, { merge: true })),
    ).toBe("permission-denied");
  });

  it("GM-FAM-051 a missed check-in creates one notice worded for helpers, never twice; a heartbeat today prevents it; silent for a day is urgent", async () => {
    // 12:00 CST on 10 February; the deadline was 11:00 and the phone was last used yesterday.
    const now = new Date("2026-02-10T18:00:00Z");
    const setDevice = (heartbeat: string) =>
      deviceRef().update({
        checkIn: { enabled: true, deadline: "11:00", timezone: "America/Chicago" },
        "status.lastHeartbeatAt": AdminTimestamp.fromDate(new Date(heartbeat)),
        checkInNotifiedFor: null,
        silentNotifiedAt: null,
      });
    const missed = deviceRef().collection("events").doc("checkin_2026-02-10");

    await setDevice("2026-02-09T20:00:00Z");
    await runCheckInSweep(admin(), now);
    expect((await missed.get()).data()).toMatchObject({
      type: "CHECK_IN_MISSED",
      severity: "NOTICE",
      source: "server",
      data: { deadline: "11:00", date: "2026-02-10" },
    });
    const { deliveredTo: to, sent } = await pushesFor(missed.path);
    expect(to).toEqual([sarah.uid]);
    expect(sent[0]!.message.notification?.title).toBe("Mom’s phone hasn’t been used yet today");

    await runCheckInSweep(admin(), now);
    await runCheckInSweep(admin(), new Date(now.getTime() + 15 * 60_000));
    const all = await deviceRef().collection("events").where("type", "==", "CHECK_IN_MISSED").where("data.date", "==", "2026-02-10").get();
    expect(all.size).toBe(1);

    // Used at 10:00 local today: nothing.
    await missed.delete();
    await setDevice("2026-02-10T16:00:00Z");
    await runCheckInSweep(admin(), now);
    expect((await missed.get()).exists).toBe(false);

    // Silent for 30 hours: the urgent "quiet for a day" notice.
    await setDevice("2026-02-09T12:00:00Z");
    await runCheckInSweep(admin(), now);
    const silent = deviceRef().collection("events").doc(`silent_${Date.parse("2026-02-09T12:00:00Z")}`);
    expect((await silent.get()).data()).toMatchObject({ type: "CHECK_IN_MISSED", severity: "URGENT", data: { reason: "silent" } });
    expect((await pushesFor(silent.path)).sent[0]!.message.notification?.title).toBe("Mom’s phone has been quiet for a day");
  });
});

// ---------------------------------------------------------------------------------------

describe("leaving", () => {
  it("GM-FAM-061 when a helper deletes a phone, that phone loses access at once and its listener ends without data", async () => {
    const { deviceId: dad } = await sarah.call<{ deviceId: string }>("addDevice", { familyId, parentName: "Dad" });
    const { code } = await sarah.call<{ code: string }>("createDeviceInvite", { familyId, deviceId: dad });
    const dadPhone = await phoneClient();
    await dadPhone.call("claimDeviceInvite", { code, model: "Moto G", androidVersion: "14", appVersion: "0.4.0" });
    const dadPath = `families/${familyId}/devices/${dad}`;

    let attached: () => void = () => undefined;
    const listening = new Promise<void>((resolve) => {
      attached = resolve;
    });
    const ended = new Promise<string>((resolve) => {
      const off = onSnapshot(
        doc(dadPhone.db, dadPath),
        (snap) => {
          if (snap.exists()) {
            attached();
            return;
          }
          off();
          resolve("deleted");
        },
        (error) => {
          resolve((error as { code?: string }).code ?? "error");
        },
      );
    });
    await listening;
    await sarah.call("deleteDevice", { familyId, deviceId: dad });
    const timeout = new Promise<string>((resolve) => {
      setTimeout(() => {
        resolve("still listening");
      }, 10_000);
    });
    expect(["deleted", "permission-denied"]).toContain(await Promise.race([ended, timeout]));
    expect(await outcome(addDoc(collection(dadPhone.db, `${dadPath}/events`), phoneEvent("HELP_REQUESTED", "URGENT", {})))).toBe("permission-denied");
    expect((await familyRef().get()).get("deviceUids")).toEqual([phone.uid]);
  });

  it("GM-FAM-060 \"Stop using Guardian Mode\" deletes the device and everything under it; the phone is out", async () => {
    await phone.call("deleteDevice", { familyId, deviceId });
    expect((await deviceRef().get()).exists).toBe(false);
    expect((await admin().doc(configPath()).get()).exists).toBe(false);
    expect((await deviceRef().collection("events").limit(1).get()).empty).toBe(true);
    expect((await deviceRef().collection("changes").limit(1).get()).empty).toBe(true);
    expect((await familyRef().get()).get("deviceUids")).toEqual([]);
    expect((await getDocs(collection(sarah.db, `families/${familyId}/devices`))).empty).toBe(true);
    expect(await outcome(getDoc(doc(phone.db, configPath())))).toBe("permission-denied");
    expect(await outcome(getDocs(collection(phone.db, `families/${familyId}/members`)))).toBe("permission-denied");
  });

  it("GM-SCAM-007 a phone talked into joining a stranger's family can't reach the old family, which no longer sees it", async () => {
    const scammer = await helperClient("Support Desk");
    const { familyId: theirs, deviceId: theirDevice } = await scammer.call<{ familyId: string; deviceId: string }>("createFamily", {
      familyName: "Support Desk",
      parentName: "Customer",
      helperPhone: "+1 555 010 9999",
    });
    const { code } = await scammer.call<{ code: string }>("createDeviceInvite", { familyId: theirs, deviceId: theirDevice });
    expect(await phone.call("claimDeviceInvite", { code })).toEqual({ familyId: theirs, deviceId: theirDevice });
    // The old family's data is out of reach, and the old family's helpers can't see the phone.
    expect(await outcome(getDocs(collection(phone.db, `families/${familyId}/members`)))).toBe("permission-denied");
    expect(await outcome(getDocs(collection(scammer.db, `families/${familyId}/devices`)))).toBe("permission-denied");
    expect((await familyRef().get()).get("deviceUids")).toEqual([]);
    await scammer.call("deleteFamily", { familyId: theirs });
  });
});
