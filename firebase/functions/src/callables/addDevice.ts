import { HttpsError, onCall, type CallableRequest } from "firebase-functions/v2/https";
import { parseInput, requireHelper } from "../lib/auth.js";
import { writeNewDevice } from "../lib/devices.js";
import { paths, requireMember, stringField } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { planLimits } from "../lib/model.js";
import { addDeviceInput } from "../lib/schemas.js";

export async function handleAddDevice(request: CallableRequest<unknown>): Promise<{ deviceId: string }> {
  const caller = requireHelper(request);
  const input = parseInput(addDeviceInput, request.data);
  const firestore = db();

  const deviceId = await firestore.runTransaction(async (tx) => {
    const family = await requireMember(firestore, input.familyId, caller.uid, tx);
    const devices = await tx.get(paths.family(firestore, input.familyId).collection("devices").select());
    const limit = planLimits(family.plan).devices;
    if (devices.size >= limit) {
      throw new HttpsError(
        "failed-precondition",
        limit === 1 ? "The free plan includes one phone." : `This plan includes up to ${limit} phones.`,
        { reason: "plan-limit", limit },
      );
    }
    const member = await tx.get(paths.member(firestore, input.familyId, caller.uid));
    const ref = writeNewDevice(firestore, tx, {
      familyId: input.familyId,
      parentName: input.parentName,
      helper: {
        uid: caller.uid,
        name: stringField(member.data(), "displayName", "Helper"),
        phone: stringField(member.data(), "phone") || undefined,
      },
      timezone: input.timezone,
    });
    return ref.id;
  });
  return { deviceId };
}

export const addDevice = onCall(handleAddDevice);
