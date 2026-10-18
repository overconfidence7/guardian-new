import { FieldValue, Timestamp } from "firebase-admin/firestore";
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { parseInput, requireHelper } from "../lib/auth.js";
import { allocateInviteCode } from "../lib/codes.js";
import { paths, requireMember, stringField } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { countPendingMembers } from "../lib/members.js";
import { HELPER_INVITE_TTL_MS, planLimits } from "../lib/model.js";
import { familyInput } from "../lib/schemas.js";

export async function handleCreateHelperInvite(
  request: CallableRequest<unknown>,
): Promise<{ code: string; expiresAt: number }> {
  const caller = requireHelper(request);
  const input = parseInput(familyInput, request.data);
  const firestore = db();
  const family = await requireMember(firestore, input.familyId, caller.uid);
  const limit = planLimits(family.plan).helpers;
  const pending = await countPendingMembers(firestore, input.familyId);
  if (family.memberUids.length + pending >= limit) {
    throw new HttpsError(
      "failed-precondition",
      limit === 1 ? "The free plan includes one helper." : `This plan includes up to ${limit} helpers.`,
      { reason: "plan-limit", limit },
    );
  }
  const member = await paths.member(firestore, input.familyId, caller.uid).get();
  const expiresAt = Date.now() + HELPER_INVITE_TTL_MS;
  const { code } = await allocateInviteCode(firestore, {
    familyId: input.familyId,
    deviceId: null,
    kind: "helper",
    createdBy: caller.uid,
    createdByName: stringField(member.data(), "displayName", "Your family"),
    createdAt: FieldValue.serverTimestamp(),
    expiresAt: Timestamp.fromMillis(expiresAt),
    usedBy: null,
  });
  return { code, expiresAt };
}

export const createHelperInvite = onCall(handleCreateHelperInvite);
