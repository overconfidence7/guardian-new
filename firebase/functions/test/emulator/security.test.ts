/*
 * docs/SECURITY.md §3 ("Sensitive changes need the parent") and the event-content promise
 * (docs/API.md "Event data keys"), checked against the Firestore and Storage rules with
 * the rules-unit-testing harness. Track B cases GM-FAM-043/044 (suspect S-17), GM-PRIV-002
 * and the Help screenshot reads of GM-HELP-003.
 *
 * Uses its own family id and never clears the emulator, so it can share it with the
 * other emulator test files.
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
import { addDoc, collection, doc, serverTimestamp, setDoc, Timestamp, updateDoc, type DocumentData, type Firestore } from "firebase/firestore";
import { getBytes, ref, uploadBytes } from "firebase/storage";
import { afterAll, beforeAll, beforeEach, describe, it } from "vitest";
import { defaultDeviceConfig } from "../../src/lib/defaultConfig.js";

const FIREBASE_DIR = fileURLToPath(new URL("../../../", import.meta.url));
const F = "fam_security";
const D = "dev_security";
const fam = (p = "") => `families/${F}${p}`;
const dev = (p = "") => fam(`/devices/${D}${p}`);
const CONFIG = dev("/config/current");
const ANYDESK = "com.anydesk.anydeskandroid";

let env: RulesTestEnvironment;

function as(uid: "sarah" | "david" | "pending" | "phone"): RulesTestContext {
  return env.authenticatedContext(`${F}_${uid}`, {
    firebase: { sign_in_provider: uid === "phone" ? "anonymous" : "password" },
  });
}
const uidOf = (who: "sarah" | "david" | "pending" | "phone") => `${F}_${who}`;
const db = (ctx: RulesTestContext): Firestore => ctx.firestore() as unknown as Firestore;

function baseConfig(): DocumentData {
  const config = defaultDeviceConfig({
    parentName: "Mom",
    helper: { uid: uidOf("sarah"), name: "Sarah", phone: "+1 555 010 1111" },
    timezone: "America/Chicago",
  });
  config.family.push({ id: `fam_${uidOf("david")}`, name: "David", relation: "", phone: "+1 555 010 2222", photoUrl: null, isPrimaryHelper: false });
  return { ...config, updatedBy: { uid: uidOf("sarah"), name: "Sarah", kind: "helper" }, updatedAt: Timestamp.now() };
}

/** David, an active helper, edits the config from the dashboard. */
const davidEdits = (fields: DocumentData) =>
  updateDoc(doc(db(as("david")), CONFIG), {
    ...fields,
    updatedBy: { uid: uidOf("david"), name: "David", kind: "helper" },
    updatedAt: serverTimestamp(),
  });

/** The parent changes the same thing on the phone. */
const parentEdits = (fields: DocumentData) =>
  updateDoc(doc(db(as("phone")), CONFIG), {
    ...fields,
    updatedBy: { uid: uidOf("phone"), name: "Mom", kind: "device" },
    updatedAt: serverTimestamp(),
  });

beforeAll(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-guardian",
    firestore: { rules: readFileSync(`${FIREBASE_DIR}firestore.rules`, "utf8") },
    storage: { rules: readFileSync(`${FIREBASE_DIR}storage.rules`, "utf8") },
  });
});

afterAll(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const d = db(ctx);
    await setDoc(doc(d, fam()), {
      name: "The Rivera family",
      plan: "family",
      ownerUid: uidOf("sarah"),
      memberUids: [uidOf("sarah"), uidOf("david")],
      deviceUids: [uidOf("phone")],
      createdAt: Timestamp.now(),
    });
    await setDoc(doc(d, fam(`/members/${uidOf("pending")}`)), { role: "helper", status: "pending", displayName: "Support Desk" });
    await setDoc(doc(d, dev()), {
      parentName: "Mom",
      deviceUid: uidOf("phone"),
      fcmToken: null,
      model: "Pixel",
      androidVersion: "16",
      appVersion: "0.4.0",
      status: { protected: true, missing: [], lastHeartbeatAt: null, lastSeenAt: null },
      installedApps: [{ packageName: ANYDESK, label: "AnyDesk" }],
      consent: null,
    });
    await setDoc(doc(d, CONFIG), baseConfig());
  });
});

// ---------------------------------------------------------------------------------------

describe("risky changes are held for the parent (docs/SECURITY.md §3, suspect S-17)", () => {
  it("GM-FAM-043 a helper can't mark a remote-control app as trusted without the parent", async () => {
    await assertFails(davidEdits({ "protection.remoteAccessAlarm.trustedPackages": [ANYDESK] }));
  });

  it("GM-FAM-044 a helper can't change a family member's phone number without the parent", async () => {
    const family = (baseConfig().family as DocumentData[]).map((f) => (f.name === "Sarah" ? { ...f, phone: "+1 555 010 9999" } : f));
    await assertFails(davidEdits({ family }));
  });

  it("GM-FAM-044 a helper can't change the primary helper without the parent", async () => {
    const family = (baseConfig().family as DocumentData[]).map((f) => ({ ...f, isPrimaryHelper: f.name === "David" }));
    await assertFails(davidEdits({ family }));
  });

  it("GM-FAM-044 a helper can't turn off pause cards from the dashboard without the parent", async () => {
    await assertFails(davidEdits({ "protection.pauseTripwires.enabled": false }));
  });

  it("GM-FAM-044 a helper can't turn off the remote-control warning from the dashboard without the parent", async () => {
    await assertFails(davidEdits({ "protection.remoteAccessAlarm.enabled": false }));
  });

  it("GM-FAM-044 a helper can't turn off long-call notices from the dashboard without the parent", async () => {
    await assertFails(davidEdits({ "protection.longCallAlert.enabled": false }));
  });

  it("GM-FAM-044 a helper can't unlock the phone remotely (lock.remoteUnlockUntil) without the parent", async () => {
    await assertFails(davidEdits({ "lock.remoteUnlockUntil": Date.now() + 10 * 60_000 }));
  });

  it("GM-FAM-044 everyday edits still apply at once: text size, tiles, reminders, turning protection on", async () => {
    await assertSucceeds(davidEdits({ "display.fontScale": 1.6 }));
    await assertSucceeds(davidEdits({ "home.columns": 3, "home.showPhotoCard": false }));
    await assertSucceeds(
      davidEdits({
        reminders: [
          { id: "r1", title: "Blood pressure pill", detail: "", kind: "MEDICATION", times: ["08:00"], daysOfWeek: [], date: null, notifyFamilyIfMissed: true },
        ],
      }),
    );
    await assertSucceeds(davidEdits({ "protection.pauseTripwires.enabled": true, "protection.safeWord.enabled": true }));
  });

  it("GM-FAM-043 GM-FAM-044 the parent may make every one of these changes on the phone", async () => {
    await assertSucceeds(parentEdits({ "protection.remoteAccessAlarm.trustedPackages": [ANYDESK] }));
    await assertSucceeds(parentEdits({ "protection.pauseTripwires.enabled": false }));
    await assertSucceeds(parentEdits({ "protection.remoteAccessAlarm.enabled": false }));
    await assertSucceeds(parentEdits({ "protection.longCallAlert.enabled": false }));
  });

  it("GM-SCAM-007 GM-FAM-021 a pending \"helper\" can't make any change, risky or not", async () => {
    const pending = db(as("pending"));
    const by = { updatedBy: { uid: uidOf("pending"), name: "Support Desk", kind: "helper" }, updatedAt: serverTimestamp() };
    await assertFails(updateDoc(doc(pending, CONFIG), { "protection.remoteAccessAlarm.trustedPackages": [ANYDESK], ...by }));
    await assertFails(updateDoc(doc(pending, CONFIG), { "display.fontScale": 1.5, ...by }));
    await assertFails(setDoc(doc(pending, CONFIG), { ...baseConfig(), ...by }));
  });
});

// ---------------------------------------------------------------------------------------

describe("events carry only the documented keys (docs/API.md \"Event data keys\")", () => {
  const event = (type: string, severity: string, data: Record<string, string>): DocumentData => ({
    type,
    severity,
    at: Date.now(),
    data,
    createdAt: serverTimestamp(),
    source: "device",
  });
  const send = (e: DocumentData) => addDoc(collection(db(as("phone")), dev("/events")), e);

  it("GM-PRIV-002 every key the phone's events use (TripwireEngine, HelpActivity, copy.ts) is accepted", async () => {
    await assertSucceeds(send(event("PAUSE_SHOWN", "NOTICE", { app: "Chase", category: "BANK", duringCall: "false", minutesSinceCall: "4" })));
    await assertSucceeds(send(event("REMOTE_ACCESS_INSTALLED", "URGENT", { app: "AnyDesk", afterUnknownCall: "true" })));
    await assertSucceeds(send(event("LONG_UNKNOWN_CALL", "URGENT", { minutes: "20", ongoing: "true" })));
    await assertSucceeds(send(event("HELP_REQUESTED", "URGENT", { path: dev("/help/h1.jpg"), note: "Is this real?" })));
    await assertSucceeds(send(event("REMINDER_MISSED", "NOTICE", { title: "Blood pressure pill", time: "08:00" })));
    await assertSucceeds(send(event("PROTECTION_CHANGED_BY_PARENT", "NOTICE", { feature: "pause", enabled: "false" })));
    await assertSucceeds(send(event("PERMISSION_MISSING", "INFO", { permission: "usageAccess" })));
  });

  // firestore.rules validEventData bounds the size of `data` but not its keys, so a phone
  // (or a tampered app) can put a caller's number or message text into an event.
  it("GM-PRIV-002 GM-FAM-046 event data with undocumented keys (a number, a message) is refused", async () => {
    await assertFails(send(event("PAUSE_SHOWN", "NOTICE", { app: "Chase", number: "+15550109999" })));
    await assertFails(send(event("LONG_UNKNOWN_CALL", "URGENT", { minutes: "20", caller: "+15550109999" })));
    await assertFails(send(event("HELP_REQUESTED", "URGENT", { note: "Is this real?", message: "Your code is 123456" })));
  });
});

// ---------------------------------------------------------------------------------------

describe("Help screenshots are for active helpers only", () => {
  const helpPath = dev("/help/shot.jpg");
  const jpeg = new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0, 0x10]);
  const storage = (ctx: RulesTestContext) => ctx.storage() as unknown as Parameters<typeof ref>[0];

  beforeEach(async () => {
    await env.withSecurityRulesDisabled(async (ctx) => {
      await uploadBytes(ref(storage(ctx), helpPath), jpeg, { contentType: "image/jpeg" });
    });
  });

  it("GM-HELP-003 GM-SCAM-007 an active helper reads the screenshot; a pending helper can't", async () => {
    await assertSucceeds(getBytes(ref(storage(as("david")), helpPath)));
    await assertFails(getBytes(ref(storage(as("pending")), helpPath)));
    await assertFails(uploadBytes(ref(storage(as("pending")), dev("/photos/fake.jpg")), jpeg, { contentType: "image/jpeg" }));
  });
});
