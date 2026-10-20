import { FieldValue } from "firebase-admin/firestore";
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { callerOf, parseInput } from "../lib/auth.js";
import { deleteInvitesWhere, deleteStoragePrefix } from "../lib/deletion.js";
import { paths, readFamily, stringField } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { messenger } from "../lib/messaging.js";
import { notificationCopy } from "../copy.js";
import { pushToHelpers } from "../triggers/onEventCreated.js";
import * as logger from "firebase-functions/logger";
import { familyDeviceInput } from "../lib/schemas.js";

/** The owner can delete any phone; a phone can delete itself ("Stop using Guardian Mode"). */
export async function handleDeleteDevice(request: CallableRequest<unknown>): Promise<Record<string, never>> {
  const caller = callerOf(request);
  const input = parseInput(familyDeviceInput, request.data);
  const firestore = db();
  const familyRef = paths.family(firestore, input.familyId);
  const deviceRef = paths.device(firestore, input.familyId, input.deviceId);

  const [familySnap, deviceSnap] = await Promise.all([familyRef.get(), deviceRef.get()]);
  const family = readFamily(familySnap);
  const deviceUid = stringField(deviceSnap.data(), "deviceUid") || null;
  const isOwner = family.ownerUid === caller.uid;
  const isThisPhone = deviceUid !== null && deviceUid === caller.uid;
  if (!isOwner && !isThisPhone) {
    if (family.memberUids.includes(caller.uid)) {
      throw new HttpsError("permission-denied", "Only the family's owner can remove a phone.");
    }
    throw new HttpsError("not-found", "That phone isn't in this family.");
  }
  if (!deviceSnap.exists) throw new HttpsError("not-found", "That phone isn't in this family.");

  // Storage first: if it fails, the Firestore records remain and the call can be retried.
  await deleteStoragePrefix(`families/${input.familyId}/devices/${input.deviceId}/`);
  await deleteInvitesWhere(firestore, "deviceId", input.deviceId);
  if (deviceUid) await familyRef.update({ deviceUids: FieldValue.arrayRemove(deviceUid) });
  await firestore.recursiveDelete(deviceRef);

  // docs/SECURITY.md §1: "leave family" is announced. The phone's own feed went with it,
  // so this goes straight to the helpers.
  const parentName = stringField(deviceSnap.data(), "parentName");
  const by = isThisPhone ? "" : stringField((await paths.member(firestore, input.familyId, caller.uid).get()).data(), "displayName", "");
  const data: Record<string, string> = by ? { by } : {};
  try {
    await pushToHelpers(firestore, messenger(), input.familyId, {
      copy: notificationCopy({ type: "PHONE_LEFT", severity: "NOTICE", data }, { parentName }),
      severity: "NOTICE",
      data: { type: "phoneLeft", familyId: input.familyId, deviceId: input.deviceId },
      tag: `left_${input.deviceId}`,
    });
  } catch (error) {
    logger.error("Phone-left notice not sent", { familyId: input.familyId, error: String(error) });
  }
  return {};
}

export const deleteDevice = onCall(handleDeleteDevice);
