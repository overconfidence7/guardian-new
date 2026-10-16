import { FieldValue, type DocumentReference, type Firestore, type Transaction, type WriteBatch } from "firebase-admin/firestore";
import { defaultDeviceConfig } from "./defaultConfig.js";
import { paths } from "./families.js";

export interface NewDeviceInput {
  familyId: string;
  parentName: string;
  helper: { uid: string; name: string; phone?: string | undefined };
  timezone?: string | undefined;
}

/** Queues the device doc and its starting config. Returns the new device's ref. */
export function writeNewDevice(db: Firestore, writer: WriteBatch | Transaction, input: NewDeviceInput): DocumentReference {
  const deviceRef = paths.family(db, input.familyId).collection("devices").doc();
  const config = defaultDeviceConfig({
    parentName: input.parentName,
    helper: { uid: input.helper.uid, name: input.helper.name, phone: input.helper.phone ?? null },
    ...(input.timezone ? { timezone: input.timezone } : {}),
  });
  writer.create(deviceRef, {
    parentName: input.parentName,
    deviceUid: null,
    fcmToken: null,
    model: "",
    androidVersion: "",
    appVersion: "",
    status: { protected: false, missing: [], lastHeartbeatAt: null, lastSeenAt: null },
    installedApps: [],
    checkIn: config.checkIn,
    checkInNotifiedFor: null,
    silentNotifiedAt: null,
    consent: null,
    createdAt: FieldValue.serverTimestamp(),
  });
  writer.create(deviceRef.collection("config").doc("current"), {
    ...config,
    updatedBy: { uid: input.helper.uid, name: input.helper.name, kind: "helper" },
    updatedAt: FieldValue.serverTimestamp(),
  });
  return deviceRef;
}
