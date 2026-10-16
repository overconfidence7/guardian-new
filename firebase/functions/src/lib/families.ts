import type { DocumentData, DocumentReference, DocumentSnapshot, Firestore, Transaction } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import type { Plan } from "./model.js";

export interface FamilyDoc {
  name: string;
  plan: Plan;
  ownerUid: string;
  memberUids: string[];
  /** Anonymous uids of the family's paired phones; maintained by functions, used by rules. */
  deviceUids: string[];
}

export const paths = {
  family: (db: Firestore, familyId: string) => db.collection("families").doc(familyId),
  member: (db: Firestore, familyId: string, uid: string) =>
    db.collection("families").doc(familyId).collection("members").doc(uid),
  device: (db: Firestore, familyId: string, deviceId: string) =>
    db.collection("families").doc(familyId).collection("devices").doc(deviceId),
  config: (db: Firestore, familyId: string, deviceId: string) =>
    db.collection("families").doc(familyId).collection("devices").doc(deviceId).collection("config").doc("current"),
  invite: (db: Firestore, code: string) => db.collection("invites").doc(code),
};

export function readFamily(snap: DocumentSnapshot): FamilyDoc {
  if (!snap.exists) throw new HttpsError("not-found", "That family doesn't exist.");
  const d = snap.data()!;
  return {
    name: typeof d.name === "string" ? d.name : "",
    plan: d.plan === "family" ? "family" : "free",
    ownerUid: typeof d.ownerUid === "string" ? d.ownerUid : "",
    memberUids: Array.isArray(d.memberUids) ? (d.memberUids as unknown[]).filter(isString) : [],
    deviceUids: Array.isArray(d.deviceUids) ? (d.deviceUids as unknown[]).filter(isString) : [],
  };
}

function isString(v: unknown): v is string {
  return typeof v === "string";
}

async function getFamily(ref: DocumentReference, tx?: Transaction): Promise<FamilyDoc> {
  return readFamily(tx ? await tx.get(ref) : await ref.get());
}

/**
 * Loads the family and checks the caller is a helper in it. Non-members get not-found
 * rather than permission-denied so family ids can't be probed.
 */
export async function requireMember(
  db: Firestore,
  familyId: string,
  uid: string,
  tx?: Transaction,
): Promise<FamilyDoc> {
  const family = await getFamily(paths.family(db, familyId), tx);
  if (!family.memberUids.includes(uid)) throw new HttpsError("not-found", "That family doesn't exist.");
  return family;
}

export async function requireOwner(
  db: Firestore,
  familyId: string,
  uid: string,
  tx?: Transaction,
): Promise<FamilyDoc> {
  const family = await requireMember(db, familyId, uid, tx);
  if (family.ownerUid !== uid) {
    throw new HttpsError("permission-denied", "Only the family's owner can do that.");
  }
  return family;
}

export function stringField(data: DocumentData | undefined, key: string, fallback = ""): string {
  const v: unknown = data?.[key];
  return typeof v === "string" ? v : fallback;
}
