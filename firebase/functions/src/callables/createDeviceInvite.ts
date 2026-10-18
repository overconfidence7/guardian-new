import { Timestamp, FieldValue } from "firebase-admin/firestore";
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { parseInput, requireHelper } from "../lib/auth.js";
import { allocateInviteCode } from "../lib/codes.js";
import { paths, requireMember, stringField } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { DEVICE_INVITE_TTL_MS } from "../lib/model.js";
import { familyDeviceInput } from "../lib/schemas.js";

export async function handleCreateDeviceInvite(
  request: CallableRequest<unknown>,
): Promise<{ code: string; expiresAt: number }> {
  const caller = requireHelper(request);
  const input = parseInput(familyDeviceInput, request.data);
  const firestore = db();
  await requireMember(firestore, input.familyId, caller.uid);
  const [device, member] = await Promise.all([
    paths.device(firestore, input.familyId, input.deviceId).get(),
    paths.member(firestore, input.familyId, caller.uid).get(),
  ]);
  if (!device.exists) throw new HttpsError("not-found", "That phone isn't in this family.");

  const expiresAt = Date.now() + DEVICE_INVITE_TTL_MS;
  const { code } = await allocateInviteCode(firestore, {
    familyId: input.familyId,
    deviceId: input.deviceId,
    kind: "device",
    createdBy: caller.uid,
    createdByName: stringField(member.data(), "displayName", "Your family"),
    createdAt: FieldValue.serverTimestamp(),
    expiresAt: Timestamp.fromMillis(expiresAt),
    usedBy: null,
  });
  return { code, expiresAt };
}

export const createDeviceInvite = onCall(handleCreateDeviceInvite);
