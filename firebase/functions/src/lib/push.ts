import { FieldValue, type DocumentReference } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import type { Messenger } from "./messaging.js";

/**
 * Sends a data-only, high-priority wake-up to the phone. A token FCM rejects for good is
 * cleared (only if the phone hasn't registered a new one meanwhile).
 */
export async function pingPhone(
  deviceRef: DocumentReference,
  fcmToken: string | null,
  type: "config" | "photo",
  messenger: Messenger,
): Promise<boolean> {
  if (!fcmToken) return false;
  const [outcome] = await messenger.send([fcmToken], { data: { type }, highPriority: true });
  if (outcome?.tokenDead) {
    await deviceRef.firestore.runTransaction(async (tx) => {
      const snap = await tx.get(deviceRef);
      if (snap.exists && snap.get("fcmToken") === fcmToken) tx.update(deviceRef, { fcmToken: null });
    });
  } else if (outcome && !outcome.ok) {
    logger.warn("Phone wake-up failed", { device: deviceRef.path, error: outcome.error });
  }
  return outcome?.ok ?? false;
}

export function removeTokens(memberRef: DocumentReference, tokens: string[]): Promise<unknown> {
  return memberRef.update({ pushTokens: FieldValue.arrayRemove(...tokens) });
}
