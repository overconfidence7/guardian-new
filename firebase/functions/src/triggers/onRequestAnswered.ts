import { FieldValue, type DocumentData, type Firestore } from "firebase-admin/firestore";
import { onDocumentUpdated } from "firebase-functions/v2/firestore";
import { askPhrase, donePhrase } from "../lib/changeRequests.js";
import { paths, stringField } from "../lib/families.js";
import { db } from "../lib/firebase.js";

export interface RequestRef {
  familyId: string;
  deviceId: string;
  requestId: string;
}

function capitalize(s: string): string {
  return s.length === 0 ? s : s[0]!.toUpperCase() + s.slice(1);
}

function isAlreadyExists(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && error.code === 6;
}

/**
 * The parent answered a helper's risky change on the phone (docs/SECURITY.md §3). Every
 * helper is told either way; an allowed change also shows in the phone's Recent changes.
 * The phone applied the change to the config itself, attributed to the device.
 */
export async function handleRequestAnswered(
  firestore: Firestore,
  ref: RequestRef,
  before: DocumentData | undefined,
  after: DocumentData | undefined,
): Promise<"approved" | "declined" | null> {
  if (!before || !after || before.status !== "pending") return null;
  const status: unknown = after.status;
  if (status !== "approved" && status !== "declined") return null;

  const deviceRef = paths.device(firestore, ref.familyId, ref.deviceId);
  const byName = stringField(after, "byName", "A helper");
  const byUid = stringField(after, "byUid");
  const approved = status === "approved";
  const writes: Promise<unknown>[] = [
    deviceRef.collection("events").doc(`req_${ref.requestId}`).create({
      type: "CHANGE_REQUEST_ANSWERED",
      severity: approved ? "URGENT" : "NOTICE",
      at: Date.now(),
      data: { byName, approved: String(approved), what: askPhrase(after.changes) },
      createdAt: FieldValue.serverTimestamp(),
      source: "server",
    }),
  ];
  if (approved) {
    writes.push(
      deviceRef.collection("changes").doc(`req_${ref.requestId}`).create({
        byUid,
        byName,
        summary: `${capitalize(donePhrase(after.changes))} after you said yes`,
        at: FieldValue.serverTimestamp(),
      }),
    );
  }
  // A retried trigger finds the docs already there.
  await Promise.all(
    writes.map(async (write) => {
      try {
        await write;
      } catch (error) {
        if (!isAlreadyExists(error)) throw error;
      }
    }),
  );
  return status;
}

export const onRequestAnswered = onDocumentUpdated(
  "families/{familyId}/devices/{deviceId}/requests/{requestId}",
  async (event) => {
    const { familyId, deviceId, requestId } = event.params;
    await handleRequestAnswered(db(), { familyId, deviceId, requestId }, event.data?.before.data(), event.data?.after.data());
  },
);
