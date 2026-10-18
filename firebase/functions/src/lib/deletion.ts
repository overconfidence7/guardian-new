import type { Firestore } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { bucket } from "./firebase.js";

function isNotFound(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && (error).code === 404;
}

/** Deletes every Storage object under the prefix. A missing bucket counts as already empty. */
export async function deleteStoragePrefix(prefix: string): Promise<void> {
  try {
    await bucket().deleteFiles({ prefix, force: true });
  } catch (error) {
    if (isNotFound(error)) return;
    logger.error("Storage cleanup failed", { prefix, error: String(error) });
    throw error;
  }
}

export async function deleteInvitesWhere(db: Firestore, field: "familyId" | "deviceId", value: string): Promise<void> {
  const snap = await db.collection("invites").where(field, "==", value).select().get();
  const writer = db.bulkWriter();
  for (const doc of snap.docs) void writer.delete(doc.ref);
  await writer.close();
}
