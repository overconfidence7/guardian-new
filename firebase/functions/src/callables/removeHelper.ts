import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { callerOf, parseInput } from "../lib/auth.js";
import { paths, readFamily, stringField } from "../lib/families.js";
import { announceToFamily } from "../lib/familyNotices.js";
import { db } from "../lib/firebase.js";
import { removeHelperInput } from "../lib/schemas.js";

interface Removal {
  change: "removed" | "left" | "declined";
  name: string;
  /** Who removed them, when it was another helper (empty when it was the parent's phone). */
  by: string;
}

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
  let removal: Removal | null = null;

  await firestore.runTransaction(async (tx) => {
    removal = null;
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
    const [target, callerMember] = await Promise.all([
      tx.get(memberRef),
      isPhone ? Promise.resolve(null) : tx.get(paths.member(firestore, input.familyId, caller.uid)),
    ]);
    const name = stringField(target.data(), "displayName", "A helper");
    if (!family.memberUids.includes(input.uid)) {
      // A pending request to help can be withdrawn the same way.
      if (!target.exists) throw new HttpsError("not-found", "That helper isn't in this family.");
      tx.delete(memberRef);
      // The parent saying no from the phone is worth telling; a withdrawn invite isn't.
      if (isPhone) removal = { change: "declined", name, by: "" };
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
    removal = isSelf && !isPhone
      ? { change: "left", name, by: "" }
      : { change: "removed", name, by: isPhone ? "" : stringField(callerMember?.data(), "displayName", "") };
  });

  // docs/SECURITY.md §3 and §6: the other helpers and the parent hear about it.
  const done = removal as Removal | null;
  if (done) {
    await announceToFamily(
      firestore,
      input.familyId,
      {
        type: "HELPER_CHANGED",
        severity: "NOTICE",
        data: { change: done.change, name: done.name, ...(done.by ? { by: done.by } : {}) },
      },
      {
        preferDeviceUid: caller.uid,
        ...(done.change === "declined"
          ? {}
          : { phoneChange: { byUid: input.uid, byName: done.name, summary: "Is no longer one of your helpers" } }),
      },
    );
  }
  return {};
}

export const removeHelper = onCall(handleRemoveHelper);
