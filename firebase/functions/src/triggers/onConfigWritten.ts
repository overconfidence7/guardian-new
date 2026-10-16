import { FieldValue, type DocumentData, type DocumentReference, type Firestore } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { onDocumentWritten } from "firebase-functions/v2/firestore";
import { summarizeConfigChange } from "../lib/configDiff.js";
import { paths, stringField } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { messenger, type Messenger } from "../lib/messaging.js";
import { readCheckIn, readDeviceConfig, type DeviceConfig } from "../lib/model.js";
import { featuresTurnedOff } from "../lib/protectionChanges.js";
import { pingPhone } from "../lib/push.js";

export interface ConfigWriteRef {
  familyId: string;
  deviceId: string;
  /** The trigger's event id; makes the docs this handler creates idempotent. */
  eventId: string;
}

export interface ConfigWriteResult {
  changeSummary: string | null;
  pingedPhone: boolean;
  protectionEvents: string[];
}

interface UpdatedBy {
  uid: string;
  name: string;
  kind: "helper" | "device" | "unknown";
}

function updatedByOf(data: DocumentData): UpdatedBy {
  const raw = (data.updatedBy ?? {}) as Record<string, unknown>;
  const kind = raw.kind === "helper" || raw.kind === "device" ? raw.kind : "unknown";
  return {
    uid: typeof raw.uid === "string" ? raw.uid : "",
    name: typeof raw.name === "string" ? raw.name : "",
    kind,
  };
}

/** Config content without bookkeeping fields, for "did anything actually change". */
function contentOf(config: DeviceConfig): string {
  const { revision: _r, ...rest } = config;
  return JSON.stringify(rest);
}

function safeId(id: string): string {
  return id.replace(/[^A-Za-z0-9_-]/g, "_").slice(0, 200);
}

function isAlreadyExists(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && (error).code === 6;
}

async function createOnce(ref: DocumentReference, data: DocumentData): Promise<boolean> {
  try {
    await ref.create(data);
    return true;
  } catch (error) {
    if (isAlreadyExists(error)) return false;
    throw error;
  }
}

function appLabels(device: DocumentData | undefined): Record<string, string> {
  const apps: unknown = device?.installedApps;
  if (!Array.isArray(apps)) return {};
  const labels: Record<string, string> = {};
  for (const app of apps as unknown[]) {
    if (app && typeof app === "object") {
      const { packageName, label } = app as Record<string, unknown>;
      if (typeof packageName === "string" && typeof label === "string") labels[packageName] = label;
    }
  }
  return labels;
}

export async function handleConfigWrite(
  firestore: Firestore,
  push: Messenger,
  ref: ConfigWriteRef,
  beforeData: DocumentData | undefined,
  afterData: DocumentData | undefined,
): Promise<ConfigWriteResult> {
  const result: ConfigWriteResult = { changeSummary: null, pingedPhone: false, protectionEvents: [] };
  if (!afterData) return result; // Deleted with its device.

  const deviceRef = paths.device(firestore, ref.familyId, ref.deviceId);
  const deviceSnap = await deviceRef.get();
  if (!deviceSnap.exists) return result;
  const device = deviceSnap.data();

  const checkIn = readCheckIn(afterData.checkIn);
  if (JSON.stringify(device?.checkIn ?? null) !== JSON.stringify(checkIn)) {
    await deviceRef.update({ checkIn });
  }

  if (!beforeData) return result; // The starting config written with the device.
  const before = readDeviceConfig(beforeData);
  const after = readDeviceConfig(afterData);
  if (!before || !after) {
    logger.warn("Config not readable; skipping change log", { device: deviceRef.path });
    return result;
  }
  if (contentOf(before) === contentOf(after)) return result;

  const by = updatedByOf(afterData);
  if (by.kind === "device") {
    const turnedOff = featuresTurnedOff(before, after);
    const eventsRef = deviceRef.collection("events");
    for (const feature of turnedOff) {
      const created = await createOnce(eventsRef.doc(`cfg_${safeId(ref.eventId)}_${feature}`), {
        type: "PROTECTION_CHANGED_BY_PARENT",
        severity: "NOTICE",
        at: Date.now(),
        data: { feature, enabled: "false" },
        createdAt: FieldValue.serverTimestamp(),
        source: "server",
      });
      if (created) result.protectionEvents.push(feature);
    }
    return result;
  }

  // A helper (or an unattributed admin edit): tell the parent and wake the phone.
  if (by.kind === "helper") {
    const summary = summarizeConfigChange(before, after, { appLabels: appLabels(device) });
    if (summary) {
      result.changeSummary = summary;
      await createOnce(deviceRef.collection("changes").doc(safeId(ref.eventId)), {
        byUid: by.uid,
        byName: by.name || "Your family",
        summary,
        at: FieldValue.serverTimestamp(),
      });
    }
  }
  result.pingedPhone = await pingPhone(deviceRef, stringField(device, "fcmToken") || null, "config", push);
  return result;
}

export const onConfigWritten = onDocumentWritten(
  "families/{familyId}/devices/{deviceId}/config/current",
  async (event) => {
    const { familyId, deviceId } = event.params;
    await handleConfigWrite(
      db(),
      messenger(),
      { familyId, deviceId, eventId: event.id },
      event.data?.before.data(),
      event.data?.after.data(),
    );
  },
);
