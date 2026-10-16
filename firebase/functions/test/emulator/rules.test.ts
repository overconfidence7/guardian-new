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
  serverTimestamp,
  setDoc,
  Timestamp,
  updateDoc,
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
    await assertFails(updateDoc(doc(db(as("helper")), dev()), { checkInNotifiedFor: null, parentName: "Mom" }));
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
  it("accepts exactly 10 data keys with 200-char values", async () => {
    const data = Object.fromEntries(Array.from({ length: 10 }, (_, i) => [`k${i}`, "v".repeat(200)]));
    await assertSucceeds(create(as("phone"), event({ data })));
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
