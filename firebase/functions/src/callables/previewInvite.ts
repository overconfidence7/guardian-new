import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { callerOf, parseInput } from "../lib/auth.js";
import { paths, readFamily, stringField } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { assertUsable, guardCodeLookup, rateKeysOf, readInvite } from "../lib/invites.js";
import { previewInviteInput } from "../lib/schemas.js";

export interface InvitePreview {
  kind: "device" | "helper";
  familyName: string;
  parentName: string;
  helperName: string;
}

/** Shows "Sarah wants to be your helper" before anything is bound. Read-only. */
export async function handlePreviewInvite(request: CallableRequest<unknown>): Promise<InvitePreview> {
  const caller = callerOf(request);
  const input = parseInput(previewInviteInput, request.data);
  const firestore = db();
  const invite = await guardCodeLookup(firestore, rateKeysOf(request, caller.uid), input.code, async (code) => {
    const found = readInvite(await paths.invite(firestore, code).get());
    assertUsable(found, found.kind, caller.uid, new Date());
    return found;
  });

  const family = readFamily(await paths.family(firestore, invite.familyId).get());
  let parentName: string;
  if (invite.deviceId) {
    const device = await paths.device(firestore, invite.familyId, invite.deviceId).get();
    if (!device.exists) throw new HttpsError("not-found", "We couldn't find that code. Please check it and try again.");
    parentName = stringField(device.data(), "parentName");
  } else {
    const first = await paths
      .family(firestore, invite.familyId)
      .collection("devices")
      .orderBy("createdAt")
      .limit(1)
      .get();
    parentName = stringField(first.docs[0]?.data(), "parentName");
  }
  return { kind: invite.kind, familyName: family.name, parentName, helperName: invite.createdByName };
}

export const previewInvite = onCall(handlePreviewInvite);
