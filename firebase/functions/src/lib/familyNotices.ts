/*
 * Telling the family about changes to the family itself (docs/SECURITY.md §3 and §6):
 * a helper added, declined or removed, a risky change allowed or declined, a phone
 * leaving. Helpers hear through a server event (delivered by onEventCreated like any
 * other, so it shows in the dashboard's feed too); the parent sees a line in the phone's
 * "Recent changes".
 */
import { FieldValue, type Firestore } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { randomUUID } from "node:crypto";
import { paths, stringField } from "./families.js";
import type { ServerEventType, Severity } from "./model.js";

export interface FamilyNotice {
  type: ServerEventType;
  severity: Severity;
  data: Record<string, string>;
}

export interface PhoneChangeLine {
  byUid: string;
  byName: string;
  summary: string;
}

export interface AnnounceOptions {
  /** Put the helpers' notice on this phone's feed when it is one of the family's. */
  preferDeviceUid?: string;
  /** A line for every phone's "Recent changes". */
  phoneChange?: PhoneChangeLine;
  /** Makes a retried call write the same docs instead of new ones. */
  id?: string;
}

/**
 * Writes the notice as a server event on one of the family's phones (helpers get it once,
 * not once per phone) and the optional change line on every phone. Best effort: a failure
 * is logged, never thrown, so the change that was already made isn't reported as failed.
 */
export async function announceToFamily(
  firestore: Firestore,
  familyId: string,
  notice: FamilyNotice,
  options: AnnounceOptions = {},
): Promise<{ eventPath: string | null }> {
  const id = options.id ?? randomUUID();
  try {
    const devices = await paths.family(firestore, familyId).collection("devices").get();
    if (devices.empty) return { eventPath: null };
    const sorted = [...devices.docs].sort((a, b) => a.id.localeCompare(b.id));
    const target =
      sorted.find((d) => options.preferDeviceUid && stringField(d.data(), "deviceUid") === options.preferDeviceUid) ??
      sorted[0]!;

    const batch = firestore.batch();
    const eventRef = target.ref.collection("events").doc(`notice_${id}`);
    batch.set(eventRef, {
      type: notice.type,
      severity: notice.severity,
      at: Date.now(),
      data: notice.data,
      createdAt: FieldValue.serverTimestamp(),
      source: "server",
    });
    if (options.phoneChange) {
      for (const device of sorted) {
        batch.set(device.ref.collection("changes").doc(`notice_${id}`), {
          ...options.phoneChange,
          at: FieldValue.serverTimestamp(),
        });
      }
    }
    await batch.commit();
    return { eventPath: eventRef.path };
  } catch (error) {
    logger.error("Family notice not written", { familyId, type: notice.type, error: String(error) });
    return { eventPath: null };
  }
}
