import { FieldValue } from "firebase-admin/firestore";
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { parseInput, requireHelper } from "../lib/auth.js";
import { paths, readFamily } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { assertUsable, guardCodeLookup, rateKeysOf, readInvite } from "../lib/invites.js";
import { countPendingMembers, memberStatus } from "../lib/members.js";
import { DEFAULT_NOTIFY, planLimits, type MemberStatus } from "../lib/model.js";
import { acceptHelperInviteInput } from "../lib/schemas.js";

/**
 * Joins a family as a pending helper. Nothing in the family is visible until the parent
 * taps "Allow" on the phone (approveHelper), so a stolen helper account can't quietly add
 * someone.
 */
export async function handleAcceptHelperInvite(
  request: CallableRequest<unknown>,
): Promise<{ familyId: string; status: MemberStatus }> {
  const caller = requireHelper(request);
  const input = parseInput(acceptHelperInviteInput, request.data);
  const firestore = db();
  const now = new Date();

  return guardCodeLookup(firestore, rateKeysOf(request, caller.uid), input.code, (code) =>
    firestore.runTransaction(async (tx) => {
      const inviteRef = paths.invite(firestore, code);
      const invite = readInvite(await tx.get(inviteRef));
      const familyRef = paths.family(firestore, invite.familyId);
      const memberRef = paths.member(firestore, invite.familyId, caller.uid);
      const [familySnap, memberSnap] = await Promise.all([tx.get(familyRef), tx.get(memberRef)]);
      const family = readFamily(familySnap);

      // Already joined (a retry, or a second invite): report where they stand.
      if (family.memberUids.includes(caller.uid)) return { familyId: invite.familyId, status: "active" as const };
      if (memberSnap.exists && memberStatus(memberSnap.data()) === "pending") {
        return { familyId: invite.familyId, status: "pending" as const };
      }

      assertUsable(invite, "helper", caller.uid, now);
      const limit = planLimits(family.plan).helpers;
      const pending = await countPendingMembers(firestore, invite.familyId, tx);
      if (family.memberUids.length + pending >= limit) {
        throw new HttpsError("failed-precondition", "This family already has as many helpers as its plan includes.", {
          reason: "plan-limit",
          limit,
        });
      }

      tx.set(memberRef, {
        role: "helper",
        status: "pending",
        displayName: input.displayName,
        email: caller.email ?? "",
        photoUrl: caller.picture,
        phone: input.phone ?? "",
        pushTokens: [],
        notify: DEFAULT_NOTIFY,
        joinedAt: FieldValue.serverTimestamp(),
      });
      tx.update(inviteRef, { usedBy: caller.uid, usedAt: FieldValue.serverTimestamp() });
      return { familyId: invite.familyId, status: "pending" as const };
    }),
  );
}

export const acceptHelperInvite = onCall(handleAcceptHelperInvite);
