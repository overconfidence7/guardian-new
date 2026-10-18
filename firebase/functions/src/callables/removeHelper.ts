import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { callerOf, parseInput } from "../lib/auth.js";
import { paths, readFamily } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { removeHelperInput } from "../lib/schemas.js";

/**
 * Removes a helper from the family. Allowed for the owner (any other helper), the
 * family's phone (any helper, so the parent is always in control) and a helper leaving
 * on their own. The last helper can't be removed; when the phone removes the owner,
 * ownership passes to the longest-standing remaining helper.
 */
export async function handleRemoveHelper(request: CallableRequest<unknown>): Promise<Record<string, never>> {
  const caller = callerOf(request);
  const input = parseInput(removeHelperInput, request.data);
  const firestore = db();

  await firestore.runTransaction(async (tx) => {
    const familyRef = paths.family(firestore, input.familyId);
    const family = readFamily(await tx.get(familyRef));
    const isPhone = family.deviceUids.includes(caller.uid);
    const isMember = family.memberUids.includes(caller.uid);
    // A pending helper isn't a member yet but may withdraw their own request.
    const isPendingSelf = caller.uid === input.uid && !isMember;
    const isOwner = family.ownerUid === caller.uid;
    const isSelf = caller.uid === input.uid;

    if (!isPhone && !isMember && !isPendingSelf) throw new HttpsError("not-found", "That family doesn't exist.");
    if (!isPhone && !isOwner && !isSelf) {
      throw new HttpsError("permission-denied", "Only the family's owner can remove other helpers.");
    }
    const memberRef = paths.member(firestore, input.familyId, input.uid);
    if (!family.memberUids.includes(input.uid)) {
      // A pending request to help can be withdrawn the same way.
      const pending = await tx.get(memberRef);
      if (!pending.exists) throw new HttpsError("not-found", "That helper isn't in this family.");
      tx.delete(memberRef);
      return;
    }

    const remaining = family.memberUids.filter((uid) => uid !== input.uid);
    if (remaining.length === 0) {
      throw new HttpsError("failed-precondition", "A family needs at least one helper.", { reason: "last-helper" });
    }

    const update: Record<string, unknown> = { memberUids: remaining };
    if (input.uid === family.ownerUid) {
      if (!isPhone) {
        throw new HttpsError("failed-precondition", "The owner can't leave the family. Delete the family instead.", {
          reason: "owner",
        });
      }
      const newOwner = remaining[0]!;
      update.ownerUid = newOwner;
      tx.update(paths.member(firestore, input.familyId, newOwner), { role: "owner" });
    }
    tx.update(familyRef, update);
    tx.delete(memberRef);
  });
  return {};
}

export const removeHelper = onCall(handleRemoveHelper);
