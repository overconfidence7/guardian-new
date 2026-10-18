import { FieldValue } from "firebase-admin/firestore";
import { onCall, type CallableRequest } from "firebase-functions/v2/https";
import { displayNameFor, parseInput, requireHelper } from "../lib/auth.js";
import { writeNewDevice } from "../lib/devices.js";
import { paths } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { DEFAULT_NOTIFY } from "../lib/model.js";
import { createFamilyInput } from "../lib/schemas.js";

export async function handleCreateFamily(request: CallableRequest<unknown>): Promise<{ familyId: string; deviceId: string }> {
  const caller = requireHelper(request);
  const input = parseInput(createFamilyInput, request.data);
  const firestore = db();
  const familyRef = firestore.collection("families").doc();
  const name = displayNameFor(caller);
  const batch = firestore.batch();

  batch.create(familyRef, {
    name: input.familyName,
    plan: "free",
    ownerUid: caller.uid,
    memberUids: [caller.uid],
    deviceUids: [],
    createdAt: FieldValue.serverTimestamp(),
  });
  batch.create(paths.member(firestore, familyRef.id, caller.uid), {
    role: "owner",
    status: "active",
    displayName: name,
    email: caller.email ?? "",
    photoUrl: caller.picture,
    phone: input.helperPhone ?? "",
    pushTokens: [],
    notify: DEFAULT_NOTIFY,
    joinedAt: FieldValue.serverTimestamp(),
  });
  const deviceRef = writeNewDevice(firestore, batch, {
    familyId: familyRef.id,
    parentName: input.parentName,
    helper: { uid: caller.uid, name, phone: input.helperPhone },
    timezone: input.timezone,
  });
  await batch.commit();
  return { familyId: familyRef.id, deviceId: deviceRef.id };
}

export const createFamily = onCall(handleCreateFamily);
