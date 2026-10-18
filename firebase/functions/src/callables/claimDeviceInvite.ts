import { FieldValue } from "firebase-admin/firestore";
import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { parseInput, requirePhone } from "../lib/auth.js";
import { paths, readFamily, stringField } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { assertUsable, guardCodeLookup, rateKeysOf, readInvite } from "../lib/invites.js";
import { claimDeviceInviteInput } from "../lib/schemas.js";

/**
 * Binds the phone's anonymous uid to the device. A previously paired phone loses access
 * at once because rules compare against devices/{d}.deviceUid.
 */
export async function handleClaimDeviceInvite(
  request: CallableRequest<unknown>,
): Promise<{ familyId: string; deviceId: string }> {
  const caller = requirePhone(request);
  const input = parseInput(claimDeviceInviteInput, request.data);
  const firestore = db();
  const now = new Date();

  return guardCodeLookup(firestore, rateKeysOf(request, caller.uid), input.code, (code) => firestore.runTransaction(async (tx) => {
    const inviteRef = paths.invite(firestore, code);
    const invite = readInvite(await tx.get(inviteRef));
    assertUsable(invite, "device", caller.uid, now);
    if (!invite.deviceId) throw new HttpsError("failed-precondition", "This code is not for a phone.");

    const familyRef = paths.family(firestore, invite.familyId);
    const deviceRef = paths.device(firestore, invite.familyId, invite.deviceId);
    const [familySnap, deviceSnap] = await Promise.all([tx.get(familyRef), tx.get(deviceRef)]);
    const family = readFamily(familySnap);
    if (!deviceSnap.exists) throw new HttpsError("not-found", "This phone was removed from the family.");

    const result = { familyId: invite.familyId, deviceId: invite.deviceId };
    const previousUid = stringField(deviceSnap.data(), "deviceUid") || null;
    if (invite.usedBy === caller.uid) {
      // A retry of a claim that already succeeded. If another phone has been paired since,
      // the old code must not hand the device back.
      if (previousUid === caller.uid) return result;
      throw new HttpsError("failed-precondition", "This code has already been used. Ask for a new one.", {
        reason: "used",
      });
    }

    let deviceUids = family.deviceUids.filter((uid) => uid !== caller.uid);
    if (previousUid && previousUid !== caller.uid) {
      // Keep the old uid only if it still drives another device in this family.
      const others = await tx.get(familyRef.collection("devices").where("deviceUid", "==", previousUid));
      if (others.docs.every((d) => d.id === invite.deviceId)) {
        deviceUids = deviceUids.filter((uid) => uid !== previousUid);
      }
    }
    deviceUids.push(caller.uid);

    tx.update(deviceRef, {
      deviceUid: caller.uid,
      fcmToken: null,
      model: input.model,
      androidVersion: input.androidVersion,
      appVersion: input.appVersion,
      // Pairing is the phone being used; it starts the check-in clock.
      "status.lastHeartbeatAt": FieldValue.serverTimestamp(),
      "status.lastSeenAt": FieldValue.serverTimestamp(),
    });
    tx.update(familyRef, { deviceUids });
    tx.update(inviteRef, { usedBy: caller.uid, usedAt: FieldValue.serverTimestamp() });
    return result;
  }));
}

export const claimDeviceInvite = onCall(handleClaimDeviceInvite);
