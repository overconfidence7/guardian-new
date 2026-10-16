import { onCall, type CallableRequest } from "firebase-functions/v2/https";
import { parseInput, requireHelper } from "../lib/auth.js";
import { deleteInvitesWhere, deleteStoragePrefix } from "../lib/deletion.js";
import { paths, requireOwner } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { familyInput } from "../lib/schemas.js";

export async function handleDeleteFamily(request: CallableRequest<unknown>): Promise<Record<string, never>> {
  const caller = requireHelper(request);
  const input = parseInput(familyInput, request.data);
  const firestore = db();
  await requireOwner(firestore, input.familyId, caller.uid);

  await deleteStoragePrefix(`families/${input.familyId}/`);
  await deleteInvitesWhere(firestore, "familyId", input.familyId);
  await firestore.recursiveDelete(paths.family(firestore, input.familyId));
  return {};
}

export const deleteFamily = onCall(handleDeleteFamily);
