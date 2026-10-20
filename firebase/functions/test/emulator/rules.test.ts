/*
 * Firestore and Storage security rules, one test per allow/deny pair in docs/API.md
 * "Rules summary". Runs against the emulators started by `npm run test:emulator`.
 */
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  type RulesTestContext,
  type RulesTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  addDoc,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
  where,
  type DocumentData,
  type Firestore,
} from "firebase/firestore";
import { getBytes, ref, uploadBytes } from "firebase/storage";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";

const FIREBASE_DIR = fileURLToPath(new URL("../../../", import.meta.url));
const PROJECT_ID = "demo-guardian";

let env: RulesTestEnvironment;

const F = "fam1";
const D = "dev1";
const fam = (p = "") => `families/${F}${p}`;
const dev = (p = "") => fam(`/devices/${D}${p}`);

function as(kind: "owner" | "helper" | "pending" | "stranger" | "phone" | "phone2" | "otherPhone"): RulesTestContext {
  const anonymous = kind === "phone" || kind === "phone2" || kind === "otherPhone";
  return env.authenticatedContext(kind, {
    firebase: { sign_in_provider: anonymous ? "anonymous" : "google.com" },
  });
}

const db = (ctx: RulesTestContext): Firestore => ctx.firestore() as unknown as Firestore;

const deviceDoc = (deviceUid: string | null): DocumentData => ({
  parentName: "Margaret",
  deviceUid,
  fcmToken: null,
  model: "Moto G",
  androidVersion: "14",
  appVersion: "1.0.0",
  status: { protected: true, missing: [], lastHeartbeatAt: null, lastSeenAt: null },
  installedApps: [],
  checkIn: { enabled: true, deadline: "11:00", timezone: "America/Chicago" },
  checkInNotifiedFor: null,
  silentNotifiedAt: null,
  consent: null,
  createdAt: Timestamp.now(),
});

const member = (role: string, status: string): DocumentData => ({
  role,
  status,
  displayName: role === "owner" ? "Sarah" : "David",
  email: "x@example.com",
  photoUrl: null,
  phone: "+1 555 010 1111",
  pushTokens: [],
  notify: { urgent: "push", notice: "push" },
  joinedAt: Timestamp.now(),
});

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: { rules: readFileSync(`${FIREBASE_DIR}firestore.rules`, "utf8") },
    storage: { rules: readFileSync(`${FIREBASE_DIR}storage.rules`, "utf8") },
  });
});

afterAll(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const d = db(ctx);
    await setDoc(doc(d, fam()), {
      name: "The Smiths",
      plan: "family",
      ownerUid: "owner",
      memberUids: ["owner", "helper"],
      deviceUids: ["phone", "phone2"],
      createdAt: Timestamp.now(),
    });
    await setDoc(doc(d, fam("/members/owner")), member("owner", "active"));
    await setDoc(doc(d, fam("/members/helper")), member("helper", "active"));
    await setDoc(doc(d, fam("/members/pending")), member("helper", "pending"));
    await setDoc(doc(d, dev()), deviceDoc("phone"));
    await setDoc(doc(d, fam("/devices/dev2")), deviceDoc("phone2"));
    await setDoc(doc(d, dev("/config/current")), {
      parentName: "Margaret",
      updatedBy: { uid: "owner", name: "Sarah", kind: "helper" },
      updatedAt: Timestamp.now(),
    });
    await setDoc(doc(d, dev("/events/e1")), {
      type: "PAUSE_SHOWN",
      severity: "NOTICE",
      at: 1,
      data: {},
      createdAt: Timestamp.now(),
      source: "device",
      deliveredTo: [],
    });
    await setDoc(doc(d, dev("/photos/p1")), {
      storagePath: `families/${F}/devices/${D}/photos/p1.jpg`,
      fromUid: "owner",
      fromName: "Sarah",
      caption: "",
      createdAt: Timestamp.now(),
    });
    await setDoc(doc(d, dev("/changes/c1")), { byUid: "owner", byName: "Sarah", summary: "Changed text size", at: Timestamp.now() });
    await setDoc(doc(d, "families/fam2"), {
      name: "Others",
      plan: "free",
      ownerUid: "stranger",
      memberUids: ["stranger"],
      deviceUids: ["otherPhone"],
    });
    await setDoc(doc(d, "families/fam2/devices/devX"), deviceDoc("otherPhone"));
    await setDoc(doc(d, "invites/ABCDEF"), { familyId: F, deviceId: D, kind: "device", usedBy: null });
  });
});

// ---------------------------------------------------------------------------------------

describe("families", () => {
  it("helpers read the family", async () => {
    await assertSucceeds(getDoc(doc(db(as("helper")), fam())));
    await assertSucceeds(getDoc(doc(db(as("owner")), fam())));
  });
  it("non-members, pending helpers, phones and the unauthenticated can't", async () => {
    await assertFails(getDoc(doc(db(as("stranger")), fam())));
    await assertFails(getDoc(doc(db(as("pending")), fam())));
    await assertFails(getDoc(doc(db(as("phone")), fam())));
    await assertFails(getDoc(doc(db(env.unauthenticatedContext()), fam())));
  });
  it("helpers list their own families, the way the dashboard does", async () => {
    const mine = (uid: "helper" | "pending") =>
      getDocs(query(collection(db(as(uid)), "families"), where("memberUids", "array-contains", uid)));
    const found = await assertSucceeds(mine("helper"));
    if (!found.docs.some((d) => d.id === F)) throw new Error("helper's family missing from the list");
    await assertSucceeds(mine("pending"));
  });
  it("nobody lists families they aren't in", async () => {
    await assertFails(getDocs(collection(db(as("helper")), "families")));
    await assertFails(getDocs(query(collection(db(as("stranger")), "families"), where("memberUids", "array-contains", "helper"))));
  });
  it("nobody writes the family doc directly", async () => {
    await assertFails(updateDoc(doc(db(as("owner")), fam()), { plan: "family" }));
    await assertFails(updateDoc(doc(db(as("helper")), fam()), { memberUids: ["helper", "stranger"] }));
    await assertFails(setDoc(doc(db(as("stranger")), "families/new"), { memberUids: ["stranger"] }));
    await assertFails(deleteDoc(doc(db(as("owner")), fam())));
  });
});

describe("members", () => {
  it("helpers read members", async () => {
    await assertSucceeds(getDocs(collection(db(as("helper")), fam("/members"))));
  });
  it("the family's phone reads member names", async () => {
    await assertSucceeds(getDoc(doc(db(as("phone")), fam("/members/owner"))));
    await assertSucceeds(getDocs(collection(db(as("phone")), fam("/members"))));
  });
  it("a pending helper reads only their own member doc", async () => {
    await assertSucceeds(getDoc(doc(db(as("pending")), fam("/members/pending"))));
    await assertFails(getDoc(doc(db(as("pending")), fam("/members/owner"))));
    await assertFails(getDocs(collection(db(as("pending")), fam("/members"))));
  });
  it("non-members and other families' phones can't read", async () => {
    await assertFails(getDoc(doc(db(as("stranger")), fam("/members/owner"))));
    await assertFails(getDoc(doc(db(as("otherPhone")), fam("/members/owner"))));
    await assertFails(getDoc(doc(db(env.unauthenticatedContext()), fam("/members/owner"))));
  });
  it("helpers update their own profile fields", async () => {
    await assertSucceeds(
      updateDoc(doc(db(as("helper")), fam("/members/helper")), {
        displayName: "Dave",
        pushTokens: ["tok"],
        notify: { urgent: "push", notice: "off" },
      }),
    );
  });
  it("helpers can't change role, status or someone else's doc", async () => {
    await assertFails(updateDoc(doc(db(as("helper")), fam("/members/helper")), { role: "owner" }));
    await assertFails(updateDoc(doc(db(as("helper")), fam("/members/helper")), { status: "pending" }));
    await assertFails(updateDoc(doc(db(as("helper")), fam("/members/owner")), { displayName: "X" }));
    await assertFails(updateDoc(doc(db(as("helper")), fam("/members/helper")), { notify: { urgent: "off", notice: "off" } }));
  });
  it("a pending helper can't activate themselves", async () => {
    await assertFails(updateDoc(doc(db(as("pending")), fam("/members/pending")), { status: "active" }));
    await assertFails(updateDoc(doc(db(as("pending")), fam("/members/pending")), { displayName: "Me" }));
  });
  it("nobody creates or deletes members directly", async () => {
    await assertFails(setDoc(doc(db(as("stranger")), fam("/members/stranger")), member("helper", "active")));
    await assertFails(setDoc(doc(db(as("owner")), fam("/members/new")), member("helper", "active")));
    await assertFails(deleteDoc(doc(db(as("owner")), fam("/members/helper"))));
    await assertFails(deleteDoc(doc(db(as("phone")), fam("/members/helper"))));
  });
});

describe("devices", () => {
  it("helpers and the paired phone read the device", async () => {
    await assertSucceeds(getDoc(doc(db(as("helper")), dev())));
    await assertSucceeds(getDoc(doc(db(as("phone")), dev())));
  });
  it("another phone, a stranger, a pending helper and the unauthenticated can't", async () => {
    await assertFails(getDoc(doc(db(as("phone2")), dev())));
    await assertFails(getDoc(doc(db(as("otherPhone")), dev())));
    await assertFails(getDoc(doc(db(as("stranger")), dev())));
    await assertFails(getDoc(doc(db(as("pending")), dev())));
    await assertFails(getDoc(doc(db(env.unauthenticatedContext()), dev())));
  });
  it("helpers update parentName only", async () => {
    await assertSucceeds(updateDoc(doc(db(as("helper")), dev()), { parentName: "Mom" }));
    await assertFails(updateDoc(doc(db(as("helper")), dev()), { parentName: "" }));
    await assertFails(updateDoc(doc(db(as("helper")), dev()), { deviceUid: "helper" }));
    await assertFails(updateDoc(doc(db(as("helper")), dev()), { fcmToken: "x" }));
    await assertFails(updateDoc(doc(db(as("helper")), dev()), { checkInNotifiedFor: "2026-10-16", parentName: "Mom" }));
  });
  it("the phone updates its reported fields", async () => {
    const phone = db(as("phone"));
    await assertSucceeds(
      updateDoc(doc(phone, dev()), {
        fcmToken: "token-1",
        model: "Galaxy A14",
        androidVersion: "15",
        appVersion: "1.1.0",
        status: { protected: false, missing: ["usageAccess"], lastHeartbeatAt: serverTimestamp(), lastSeenAt: serverTimestamp() },
        installedApps: [{ packageName: "com.whatsapp", label: "WhatsApp" }],
        consent: { version: 1, acceptedAt: serverTimestamp(), features: { pause: true } },
      }),
    );
    await assertSucceeds(updateDoc(doc(phone, dev()), { "status.lastHeartbeatAt": serverTimestamp() }));
  });
  it("the phone can't touch other fields or other devices", async () => {
    const phone = db(as("phone"));
    await assertFails(updateDoc(doc(phone, dev()), { deviceUid: "someone" }));
    await assertFails(updateDoc(doc(phone, dev()), { parentName: "Me" }));
    await assertFails(updateDoc(doc(phone, dev()), { checkInNotifiedFor: "2026-10-16" }));
    await assertFails(updateDoc(doc(phone, dev()), { silentNotifiedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(phone, dev()), { checkIn: { enabled: false } }));
    await assertFails(updateDoc(doc(phone, dev()), { status: { protected: true, hacked: true } }));
    await assertFails(updateDoc(doc(phone, dev()), { model: "x".repeat(101) }));
    await assertFails(updateDoc(doc(phone, fam("/devices/dev2")), { fcmToken: "t" }));
    await assertFails(updateDoc(doc(db(as("otherPhone")), dev()), { fcmToken: "t" }));
  });
  it("nobody creates or deletes devices directly", async () => {
    await assertFails(setDoc(doc(db(as("owner")), fam("/devices/new")), deviceDoc(null)));
    await assertFails(deleteDoc(doc(db(as("owner")), dev())));
    await assertFails(deleteDoc(doc(db(as("phone")), dev())));
  });
});

describe("config", () => {
  const helperWrite = (uid: string, name = "Sarah") => ({
    parentName: "Margaret",
    display: { fontScale: 1.5 },
    updatedBy: { uid, name, kind: "helper" },
    updatedAt: serverTimestamp(),
  });
  const deviceWrite = (uid: string) => ({
    parentName: "Margaret",
    protection: { longCallAlert: { enabled: false, minutes: 20 } },
    updatedBy: { uid, name: "Margaret", kind: "device" },
    updatedAt: serverTimestamp(),
  });

  it("helpers and the phone read config", async () => {
    await assertSucceeds(getDoc(doc(db(as("helper")), dev("/config/current"))));
    await assertSucceeds(getDoc(doc(db(as("phone")), dev("/config/current"))));
  });
  it("others can't read config", async () => {
    await assertFails(getDoc(doc(db(as("stranger")), dev("/config/current"))));
    await assertFails(getDoc(doc(db(as("phone2")), dev("/config/current"))));
    await assertFails(getDoc(doc(db(as("pending")), dev("/config/current"))));
    await assertFails(getDoc(doc(db(env.unauthenticatedContext()), dev("/config/current"))));
  });
  it("helpers write config attributed to themselves", async () => {
    await assertSucceeds(setDoc(doc(db(as("helper")), dev("/config/current")), helperWrite("helper", "David")));
  });
  it("helpers can't write config attributed to someone else or to the device", async () => {
    await assertFails(setDoc(doc(db(as("helper")), dev("/config/current")), helperWrite("owner")));
    await assertFails(setDoc(doc(db(as("helper")), dev("/config/current")), deviceWrite("helper")));
    await assertFails(
      setDoc(doc(db(as("helper")), dev("/config/current")), { ...helperWrite("helper"), updatedAt: Timestamp.fromMillis(0) }),
    );
  });
  it("the phone writes config attributed to the device", async () => {
    await assertSucceeds(setDoc(doc(db(as("phone")), dev("/config/current")), deviceWrite("phone")));
  });
  it("the phone can't pose as a helper; strangers and other phones can't write", async () => {
    await assertFails(setDoc(doc(db(as("phone")), dev("/config/current")), helperWrite("phone")));
    await assertFails(setDoc(doc(db(as("stranger")), dev("/config/current")), helperWrite("stranger")));
    await assertFails(setDoc(doc(db(as("phone2")), dev("/config/current")), deviceWrite("phone2")));
    await assertFails(setDoc(doc(db(as("pending")), dev("/config/current")), helperWrite("pending")));
  });
  it("only config/current exists", async () => {
    await assertFails(setDoc(doc(db(as("helper")), dev("/config/other")), helperWrite("helper")));
  });
  it("config can't be deleted", async () => {
    await assertFails(deleteDoc(doc(db(as("owner")), dev("/config/current"))));
  });
});

describe("config: changes only the parent can make (docs/SECURITY.md §3)", () => {
  const CONFIG = dev("/config/current");
  const family = () => [
    { id: "fam_owner", name: "Sarah", relation: "Daughter", phone: "+1 555 010 1111", photoUrl: null, isPrimaryHelper: true },
    { id: "fam_helper", name: "David", relation: "Son", phone: "+1 555 010 2222", photoUrl: null, isPrimaryHelper: false },
  ];
  const by = (uid: string, kind: "helper" | "device") => ({
    updatedBy: { uid, name: kind === "helper" ? "David" : "Margaret", kind },
    updatedAt: serverTimestamp(),
  });
  const helperEdits = (fields: DocumentData) => updateDoc(doc(db(as("helper")), CONFIG), { ...fields, ...by("helper", "helper") });
  const parentEdits = (fields: DocumentData) => updateDoc(doc(db(as("phone")), CONFIG), { ...fields, ...by("phone", "device") });

  beforeEach(async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(db(ctx), CONFIG), {
        parentName: "Margaret",
        family: family(),
        protection: {
          pauseTripwires: { enabled: true, windowMinutes: 30 },
          remoteAccessAlarm: { enabled: true, trustedPackages: ["com.teamviewer.host"], warnOnControlCapableInstalls: true },
          longCallAlert: { enabled: true, minutes: 20 },
        },
        lock: { level: "GUIDED", remoteUnlockUntil: 0 },
        updatedBy: { uid: "owner", name: "Sarah", kind: "helper" },
        updatedAt: Timestamp.now(),
      });
    });
  });

  it("a helper can't trust a remote-control app, change a number or the primary helper", async () => {
    await assertFails(helperEdits({ "protection.remoteAccessAlarm.trustedPackages": ["com.teamviewer.host", "com.anydesk.anydeskandroid"] }));
    await assertFails(helperEdits({ family: family().map((f) => (f.name === "Sarah" ? { ...f, phone: "+1 555 010 9999" } : f)) }));
    await assertFails(helperEdits({ family: family().map((f) => ({ ...f, isPrimaryHelper: f.name === "David" })) }));
    await assertFails(helperEdits({ family: [...family(), { id: "fam_x", name: "Bank", relation: "", phone: "+1 555 010 0000", photoUrl: null, isPrimaryHelper: false }] }));
  });
  it("a helper can't turn off pause cards, the remote-control warning or long-call notices, or unlock remotely", async () => {
    await assertFails(helperEdits({ "protection.pauseTripwires.enabled": false }));
    await assertFails(helperEdits({ "protection.remoteAccessAlarm.enabled": false }));
    await assertFails(helperEdits({ "protection.longCallAlert.enabled": false }));
    await assertFails(helperEdits({ "lock.remoteUnlockUntil": Date.now() + 10 * 60_000 }));
    // Dropping the whole protection map doesn't sneak past either.
    await assertFails(setDoc(doc(db(as("helper")), CONFIG), { parentName: "Margaret", family: family(), protection: { pauseTripwires: { enabled: false } }, ...by("helper", "helper") }));
  });
  it("everyday edits, and edits that make things safer, apply at once", async () => {
    await assertSucceeds(helperEdits({ "display.fontScale": 1.6, "home.columns": 3 }));
    await assertSucceeds(helperEdits({ family: family().map((f) => (f.name === "David" ? { ...f, name: "Dave", relation: "Brother" } : f)) }));
    await assertSucceeds(helperEdits({ "protection.remoteAccessAlarm.trustedPackages": [] }));
    await assertSucceeds(helperEdits({ "protection.pauseTripwires.windowMinutes": 45, "protection.safeWord.enabled": false }));
    await assertSucceeds(helperEdits({ "protection.pauseTripwires.enabled": true }));
  });
  it("a helper can turn a protection back on that the parent turned off", async () => {
    await parentEdits({ "protection.longCallAlert.enabled": false });
    await assertSucceeds(helperEdits({ "protection.longCallAlert.enabled": true }));
  });
  it("the parent's phone may make every one of these changes", async () => {
    await assertSucceeds(parentEdits({ "protection.remoteAccessAlarm.trustedPackages": ["com.anydesk.anydeskandroid"] }));
    await assertSucceeds(parentEdits({ "protection.pauseTripwires.enabled": false, "protection.longCallAlert.enabled": false }));
    await assertSucceeds(parentEdits({ "protection.remoteAccessAlarm.enabled": false, "lock.remoteUnlockUntil": Date.now() + 600_000 }));
    await assertSucceeds(parentEdits({ family: family().map((f) => ({ ...f, isPrimaryHelper: f.name === "David" })) }));
  });
  it("before a phone is paired there's no parent to ask, so helpers set it all up", async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await updateDoc(doc(db(ctx), dev()), { deviceUid: null });
    });
    await assertSucceeds(helperEdits({ family: family().map((f) => (f.name === "Sarah" ? { ...f, phone: "+1 555 010 9999" } : f)) }));
    await assertSucceeds(helperEdits({ "protection.pauseTripwires.enabled": false }));
  });
});

describe("requests (risky changes waiting for the parent)", () => {
  const REQUESTS = dev("/requests");
  const request = (overrides: DocumentData = {}): DocumentData => ({
    byUid: "helper",
    byName: "David",
    changes: { pauseTripwiresEnabled: false },
    status: "pending",
    createdAt: serverTimestamp(),
    ...overrides,
  });
  const seed = async (id: string, data: DocumentData) =>
    env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(db(ctx), `${REQUESTS}/${id}`), { ...data, createdAt: Timestamp.now() });
    });

  it("a helper asks for a risky change", async () => {
    await assertSucceeds(addDoc(collection(db(as("helper")), REQUESTS), request()));
    await assertSucceeds(
      addDoc(
        collection(db(as("helper")), REQUESTS),
        request({
          changes: {
            family: [{ id: "fam_owner", name: "Sarah", phone: "+1 555 010 9999", isPrimaryHelper: true }],
            trustedPackages: ["com.anydesk.anydeskandroid"],
            remoteAccessAlarmEnabled: false,
            longCallAlertEnabled: false,
            remoteUnlockUntil: Date.now() + 600_000,
          },
        }),
      ),
    );
  });
  it("requests are checked: own name, pending, known keys, sane values", async () => {
    const helper = collection(db(as("helper")), REQUESTS);
    await assertFails(addDoc(helper, request({ byUid: "owner" })));
    await assertFails(addDoc(helper, request({ status: "approved" })));
    await assertFails(addDoc(helper, request({ changes: {} })));
    await assertFails(addDoc(helper, request({ changes: { "display.fontScale": 2 } })));
    await assertFails(addDoc(helper, request({ changes: { pauseTripwiresEnabled: "no" } })));
    await assertFails(addDoc(helper, request({ changes: { remoteUnlockUntil: Date.now() + 3 * 86_400_000 } })));
    await assertFails(addDoc(helper, request({ createdAt: Timestamp.fromMillis(0) })));
    await assertFails(addDoc(helper, request({ summary: "Just a font change" })));
  });
  it("pending helpers, strangers and phones can't ask", async () => {
    await assertFails(addDoc(collection(db(as("pending")), REQUESTS), request({ byUid: "pending" })));
    await assertFails(addDoc(collection(db(as("stranger")), REQUESTS), request({ byUid: "stranger" })));
    await assertFails(addDoc(collection(db(as("phone")), REQUESTS), request({ byUid: "phone" })));
  });
  it("helpers and this phone read requests; others don't", async () => {
    await seed("r1", request());
    await assertSucceeds(getDoc(doc(db(as("helper")), `${REQUESTS}/r1`)));
    await assertSucceeds(getDoc(doc(db(as("phone")), `${REQUESTS}/r1`)));
    await assertFails(getDoc(doc(db(as("phone2")), `${REQUESTS}/r1`)));
    await assertFails(getDoc(doc(db(as("stranger")), `${REQUESTS}/r1`)));
    await assertFails(getDoc(doc(db(as("pending")), `${REQUESTS}/r1`)));
  });
  it("only this phone answers, once, and only with the status", async () => {
    await seed("r1", request());
    const answer = (ctx: RulesTestContext, status: string, extra: DocumentData = {}) =>
      updateDoc(doc(db(ctx), `${REQUESTS}/r1`), { status, decidedAt: serverTimestamp(), ...extra });
    await assertFails(answer(as("helper"), "approved"));
    await assertFails(answer(as("owner"), "approved"));
    await assertFails(answer(as("phone2"), "approved"));
    await assertFails(answer(as("phone"), "maybe"));
    await assertFails(answer(as("phone"), "approved", { changes: { pauseTripwiresEnabled: true } }));
    await assertSucceeds(answer(as("phone"), "approved"));
    await assertFails(answer(as("phone"), "declined"));
  });
  it("the helper who asked may withdraw a pending request; nobody else deletes", async () => {
    await seed("r1", request());
    await seed("r2", request({ byUid: "owner", byName: "Sarah" }));
    await assertFails(deleteDoc(doc(db(as("helper")), `${REQUESTS}/r2`)));
    await assertFails(deleteDoc(doc(db(as("phone")), `${REQUESTS}/r1`)));
    await assertSucceeds(deleteDoc(doc(db(as("helper")), `${REQUESTS}/r1`)));
  });
});

describe("events", () => {
  const event = (overrides: DocumentData = {}): DocumentData => ({
    type: "PAUSE_SHOWN",
    severity: "NOTICE",
    at: 1_760_000_000_000,
    data: { app: "Chase", duringCall: "false", minutesSinceCall: "4" },
    createdAt: serverTimestamp(),
    source: "device",
    ...overrides,
  });
  const create = (ctx: RulesTestContext, e: DocumentData) => addDoc(collection(db(ctx), dev("/events")), e);

  it("the phone creates a valid event", async () => {
    await assertSucceeds(create(as("phone"), event()));
    await assertSucceeds(create(as("phone"), event({ data: {} })));
    const { data: _data, ...noData } = event();
    await assertSucceeds(create(as("phone"), noData));
  });
  it("accepts 200-char values", async () => {
    await assertSucceeds(create(as("phone"), event({ data: { app: "v".repeat(200), category: "BANK" } })));
  });
  it("accepts every key the phone's events use (TripwireEngine, HelpActivity, ReminderScheduler)", async () => {
    const ok = (type: string, data: Record<string, string>, severity = "NOTICE") =>
      assertSucceeds(create(as("phone"), event({ type, severity, data })));
    await ok("PAUSE_SHOWN", { app: "Chase", category: "BANK", duringCall: "false", minutesSinceCall: "4" });
    await ok("PAUSE_CALLED_FAMILY", { app: "Chase" });
    await ok("PAUSE_CONTINUED", { app: "Chase" });
    await ok("REMOTE_ACCESS_INSTALLED", { app: "AnyDesk", afterUnknownCall: "true" }, "URGENT");
    await ok("REMOTE_ACCESS_OPENED", { app: "AnyDesk", afterUnknownCall: "false" });
    await ok("CONTROL_APP_INSTALLED", { app: "Helper", afterUnknownCall: "false" });
    await ok("LONG_UNKNOWN_CALL", { minutes: "20", ongoing: "true" }, "URGENT");
    await ok("HELP_REQUESTED", { path: dev("/help/h1.jpg"), note: "Is this real?" }, "URGENT");
    await ok("REMINDER_MISSED", { title: "Blood pressure pill", time: "08:00" });
    await ok("PROTECTION_CHANGED_BY_PARENT", { feature: "pause", enabled: "false" });
    await ok("PERMISSION_MISSING", { permission: "usageAccess" }, "INFO");
  });
  it("refuses undocumented data keys: no number, caller or message can ride along", async () => {
    await assertFails(create(as("phone"), event({ data: { app: "Chase", number: "+15550109999" } })));
    await assertFails(create(as("phone"), event({ type: "LONG_UNKNOWN_CALL", severity: "URGENT", data: { minutes: "20", caller: "+15550109999" } })));
    await assertFails(create(as("phone"), event({ type: "HELP_REQUESTED", data: { note: "Is this real?", message: "Your code is 123456" } })));
    // A key documented for one type isn't allowed on another.
    await assertFails(create(as("phone"), event({ type: "PERMISSION_MISSING", data: { permission: "overlay", app: "Chase" } })));
  });
  it("rejects a bad type or severity", async () => {
    await assertFails(create(as("phone"), event({ type: "SMS_READ" })));
    await assertFails(create(as("phone"), event({ type: "CHECK_IN_MISSED" })));
    await assertFails(create(as("phone"), event({ severity: "CRITICAL" })));
  });
  it("rejects too many data keys, long values or non-string values", async () => {
    const eleven = Object.fromEntries(Array.from({ length: 11 }, (_, i) => [`k${i}`, "v"]));
    await assertFails(create(as("phone"), event({ data: eleven })));
    await assertFails(create(as("phone"), event({ data: { app: "x".repeat(201) } })));
    await assertFails(create(as("phone"), event({ data: { minutes: 20 } })));
    await assertFails(create(as("phone"), event({ data: { a: "1", b: "2", c: "3", d: "4", e: "5", f: "6", g: "7", h: "8", i: "9", j: { k: "nested" } } })));
    await assertFails(create(as("phone"), event({ data: "app=Chase" })));
  });
  it("rejects source != device, extra fields, client timestamps and deliveredTo", async () => {
    await assertFails(create(as("phone"), event({ source: "server" })));
    await assertFails(create(as("phone"), event({ number: "+15550101111" })));
    await assertFails(create(as("phone"), event({ deliveredTo: ["owner"] })));
    await assertFails(create(as("phone"), event({ createdAt: Timestamp.fromMillis(0) })));
    await assertFails(create(as("phone"), event({ at: "yesterday" })));
  });
  it("helpers, strangers, other phones and the unauthenticated can't create events", async () => {
    await assertFails(create(as("helper"), event()));
    await assertFails(create(as("stranger"), event()));
    await assertFails(create(as("phone2"), event()));
    await assertFails(create(as("otherPhone"), event()));
    await assertFails(create(env.unauthenticatedContext(), event()));
  });
  it("helpers read events; the phone, strangers and pending helpers don't", async () => {
    await assertSucceeds(getDocs(collection(db(as("helper")), dev("/events"))));
    await assertFails(getDoc(doc(db(as("phone")), dev("/events/e1"))));
    await assertFails(getDoc(doc(db(as("stranger")), dev("/events/e1"))));
    await assertFails(getDoc(doc(db(as("pending")), dev("/events/e1"))));
  });
  it("events are immutable to clients", async () => {
    await assertFails(updateDoc(doc(db(as("phone")), dev("/events/e1")), { severity: "INFO" }));
    await assertFails(updateDoc(doc(db(as("owner")), dev("/events/e1")), { deliveredTo: [] }));
    await assertFails(deleteDoc(doc(db(as("owner")), dev("/events/e1"))));
    await assertFails(deleteDoc(doc(db(as("phone")), dev("/events/e1"))));
  });
});

describe("photos", () => {
  const photo = (fromUid: string): DocumentData => ({
    storagePath: `families/${F}/devices/${D}/photos/p2.jpg`,
    fromUid,
    fromName: "Sarah",
    caption: "Emma's recital",
    createdAt: serverTimestamp(),
  });
  it("helpers and the phone read photos", async () => {
    await assertSucceeds(getDoc(doc(db(as("helper")), dev("/photos/p1"))));
    await assertSucceeds(getDoc(doc(db(as("phone")), dev("/photos/p1"))));
  });
  it("others can't read photos", async () => {
    await assertFails(getDoc(doc(db(as("stranger")), dev("/photos/p1"))));
    await assertFails(getDoc(doc(db(as("phone2")), dev("/photos/p1"))));
  });
  it("helpers add, recaption and delete photos", async () => {
    await assertSucceeds(setDoc(doc(db(as("owner")), dev("/photos/p2")), photo("owner")));
    await assertSucceeds(updateDoc(doc(db(as("helper")), dev("/photos/p1")), { caption: "New" }));
    await assertSucceeds(deleteDoc(doc(db(as("helper")), dev("/photos/p1"))));
  });
  it("photo writes are checked", async () => {
    await assertFails(setDoc(doc(db(as("helper")), dev("/photos/p2")), photo("owner")));
    await assertFails(
      setDoc(doc(db(as("owner")), dev("/photos/p2")), { ...photo("owner"), storagePath: "families/fam2/devices/devX/photos/a.jpg" }),
    );
    await assertFails(updateDoc(doc(db(as("helper")), dev("/photos/p1")), { fromUid: "helper" }));
  });
  it("the phone and strangers can't write photos", async () => {
    await assertFails(setDoc(doc(db(as("phone")), dev("/photos/p2")), photo("phone")));
    await assertFails(setDoc(doc(db(as("stranger")), dev("/photos/p2")), photo("stranger")));
    await assertFails(deleteDoc(doc(db(as("phone")), dev("/photos/p1"))));
  });
});

describe("changes", () => {
  it("helpers and the phone read changes", async () => {
    await assertSucceeds(getDoc(doc(db(as("helper")), dev("/changes/c1"))));
    await assertSucceeds(getDocs(collection(db(as("phone")), dev("/changes"))));
  });
  it("others can't read; nobody writes", async () => {
    await assertFails(getDoc(doc(db(as("stranger")), dev("/changes/c1"))));
    await assertFails(setDoc(doc(db(as("owner")), dev("/changes/c2")), { summary: "fake" }));
    await assertFails(setDoc(doc(db(as("phone")), dev("/changes/c2")), { summary: "fake" }));
    await assertFails(deleteDoc(doc(db(as("phone")), dev("/changes/c1"))));
  });
});

describe("invites and server-only collections", () => {
  it("no client access to invites", async () => {
    for (const who of ["owner", "phone", "stranger"] as const) {
      await assertFails(getDoc(doc(db(as(who)), "invites/ABCDEF")));
      await assertFails(updateDoc(doc(db(as(who)), "invites/ABCDEF"), { usedBy: who }));
      await assertFails(setDoc(doc(db(as(who)), "invites/ZZZZZZ"), { familyId: F }));
    }
    await assertFails(getDoc(doc(db(env.unauthenticatedContext()), "invites/ABCDEF")));
  });
  it("no client access to rate limits or unknown paths", async () => {
    await assertFails(getDoc(doc(db(as("owner")), "rateLimits/uid_owner")));
    await assertFails(setDoc(doc(db(as("phone")), "rateLimits/uid_phone"), { failures: 0 }));
    await assertFails(setDoc(doc(db(as("owner")), "anything/else"), { a: 1 }));
  });
});

// ---------------------------------------------------------------------------------------

describe("storage", () => {
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0x10]);
  const photoPath = `families/${F}/devices/${D}/photos/p2.jpg`;
  const helpPath = `families/${F}/devices/${D}/help/h1.jpg`;
  const storage = (ctx: RulesTestContext) => ctx.storage() as unknown as Parameters<typeof ref>[0];
  const upload = (ctx: RulesTestContext, path: string, bytes: Uint8Array, contentType: string) =>
    uploadBytes(ref(storage(ctx), path), bytes, { contentType });

  beforeEach(async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await uploadBytes(ref(storage(ctx), `families/${F}/devices/${D}/photos/p1.jpg`), jpeg, { contentType: "image/jpeg" });
      await uploadBytes(ref(storage(ctx), `families/${F}/devices/${D}/help/h0.jpg`), jpeg, { contentType: "image/jpeg" });
    });
  });

  it("helpers upload photos; helpers and the phone read them", async () => {
    await assertSucceeds(upload(as("owner"), photoPath, jpeg, "image/jpeg"));
    await assertSucceeds(upload(as("helper"), `families/${F}/devices/${D}/photos/p3.jpg`, jpeg, "image/webp"));
    await assertSucceeds(getBytes(ref(storage(as("helper")), `families/${F}/devices/${D}/photos/p1.jpg`)));
    await assertSucceeds(getBytes(ref(storage(as("phone")), `families/${F}/devices/${D}/photos/p1.jpg`)));
  });
  it("photo uploads must be images up to 10 MB, named *.jpg", async () => {
    await assertFails(upload(as("owner"), photoPath, jpeg, "application/pdf"));
    await assertFails(upload(as("owner"), photoPath, new Uint8Array(10 * 1024 * 1024 + 1), "image/jpeg"));
    await assertFails(upload(as("owner"), `families/${F}/devices/${D}/photos/p2.png`, jpeg, "image/png"));
  });
  it("the phone, strangers, pending helpers and the unauthenticated can't upload photos", async () => {
    await assertFails(upload(as("phone"), photoPath, jpeg, "image/jpeg"));
    await assertFails(upload(as("stranger"), photoPath, jpeg, "image/jpeg"));
    await assertFails(upload(as("pending"), photoPath, jpeg, "image/jpeg"));
    await assertFails(upload(env.unauthenticatedContext(), photoPath, jpeg, "image/jpeg"));
  });
  it("strangers and other phones can't read photos", async () => {
    await assertFails(getBytes(ref(storage(as("stranger")), `families/${F}/devices/${D}/photos/p1.jpg`)));
    await assertFails(getBytes(ref(storage(as("phone2")), `families/${F}/devices/${D}/photos/p1.jpg`)));
  });
  it("the phone uploads help screenshots; helpers read them", async () => {
    await assertSucceeds(upload(as("phone"), helpPath, jpeg, "image/jpeg"));
    await assertSucceeds(getBytes(ref(storage(as("helper")), `families/${F}/devices/${D}/help/h0.jpg`)));
  });
  it("help screenshots: JPEG only, up to 5 MB, only this device's phone", async () => {
    await assertFails(upload(as("phone"), helpPath, jpeg, "image/png"));
    await assertFails(upload(as("phone"), helpPath, new Uint8Array(5 * 1024 * 1024 + 1), "image/jpeg"));
    await assertFails(upload(as("phone2"), helpPath, jpeg, "image/jpeg"));
    await assertFails(upload(as("helper"), helpPath, jpeg, "image/jpeg"));
  });
  it("help screenshots aren't readable by the phone or strangers", async () => {
    await assertFails(getBytes(ref(storage(as("phone")), `families/${F}/devices/${D}/help/h0.jpg`)));
    await assertFails(getBytes(ref(storage(as("stranger")), `families/${F}/devices/${D}/help/h0.jpg`)));
  });
  it("everything else in the bucket is closed", async () => {
    await assertFails(upload(as("owner"), "public/x.jpg", jpeg, "image/jpeg"));
    await assertFails(upload(as("owner"), `families/${F}/other.jpg`, jpeg, "image/jpeg"));
  });
});
