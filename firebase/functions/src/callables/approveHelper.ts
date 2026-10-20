import { FieldValue } from "firebase-admin/firestore";
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { callerOf, parseInput } from "../lib/auth.js";
import { paths, readFamily, stringField } from "../lib/families.js";
import { announceToFamily } from "../lib/familyNotices.js";
import { db } from "../lib/firebase.js";
import { memberStatus, prepareFamilyContactAdd } from "../lib/members.js";
import { planLimits } from "../lib/model.js";
import { approveHelperInput } from "../lib/schemas.js";

/** The parent's answer on the phone to "Allow David to help?". Only the family's phone may call it. */
export async function handleApproveHelper(request: CallableRequest<unknown>): Promise<Record<string, never>> {
  const caller = callerOf(request);
  const input = parseInput(approveHelperInput, request.data);
  const firestore = db();
  let outcome: HelperOutcome | null = null;

  await firestore.runTransaction(async (tx) => {
    outcome = null;
    const familyRef = paths.family(firestore, input.familyId);
    const memberRef = paths.member(firestore, input.familyId, input.uid);
    const [familySnap, memberSnap] = await Promise.all([tx.get(familyRef), tx.get(memberRef)]);
    if (!familySnap.exists) throw new HttpsError("not-found", "That family doesn't exist.");
    const family = readFamily(familySnap);
    if (!caller.anonymous || !family.deviceUids.includes(caller.uid)) {
      throw new HttpsError("permission-denied", "Only the parent's phone can approve a new helper.");
    }
    if (!memberSnap.exists) throw new HttpsError("not-found", "That request to help is no longer there.");
    const member = memberSnap.data();

    if (memberStatus(member) === "active") {
      if (input.approve) return; // Already approved: a retried tap.
      throw new HttpsError("failed-precondition", "This person is already a helper. Use remove instead.", {
        reason: "already-active",
      });
    }

    const name = stringField(member, "displayName", "Helper");
    if (!input.approve) {
      tx.delete(memberRef);
      outcome = { change: "declined", name };
      return;
    }

    const limit = planLimits(family.plan).helpers;
    if (family.memberUids.length >= limit) {
      throw new HttpsError("failed-precondition", "This family already has as many helpers as its plan includes.", {
        reason: "plan-limit",
        limit,
      });
    }
    const phone = stringField(member, "phone");
    const addContact = phone ? await prepareFamilyContactAdd(firestore, tx, input.familyId) : null;

    tx.update(memberRef, { status: "active", approvedAt: FieldValue.serverTimestamp() });
    tx.update(familyRef, { memberUids: FieldValue.arrayUnion(input.uid) });
    addContact?.({ uid: input.uid, name, phone }, { uid: caller.uid, name: "", kind: "device" });
    outcome = { change: "added", name };
  });

  // docs/SECURITY.md §3 and §6: every helper hears about it either way, and a new
  // helper shows in the phone's "Recent changes". A retried tap (already active) is quiet.
  const done = outcome as HelperOutcome | null;
  if (done) {
    await announceToFamily(
      firestore,
      input.familyId,
      {
        type: "HELPER_CHANGED",
        severity: done.change === "added" ? "URGENT" : "NOTICE",
        data: { change: done.change, name: done.name },
      },
      {
        preferDeviceUid: caller.uid,
        ...(done.change === "added"
          ? { phoneChange: { byUid: input.uid, byName: done.name, summary: "Became one of your helpers" } }
          : {}),
      },
    );
  }
  return {};
}

interface HelperOutcome {
  change: "added" | "declined";
  name: string;
}

export const approveHelper = onCall(handleApproveHelper);
