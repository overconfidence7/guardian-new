import { FieldValue, type DocumentData, type Firestore, type Transaction } from "firebase-admin/firestore";
import { familyContactIdFor, helperFamilyContact } from "./defaultConfig.js";
import { paths } from "./families.js";
import type { MemberStatus } from "./model.js";
import { readDeviceConfig } from "./model.js";

/** Members written before `status` existed count as active. */
export function memberStatus(data: DocumentData | undefined): MemberStatus {
  return data?.status === "pending" ? "pending" : "active";
}

export async function countPendingMembers(db: Firestore, familyId: string, tx?: Transaction): Promise<number> {
  const query = paths.family(db, familyId).collection("members").where("status", "==", "pending").select();
  const snap = tx ? await tx.get(query) : await query.get();
  return snap.size;
}

/**
 * Adds a helper to every phone's family list so "Call ___" can reach them. Split into a
 * read phase and a write phase because transactions need all reads before any write.
 */
export async function prepareFamilyContactAdd(
  db: Firestore,
  tx: Transaction,
  familyId: string,
): Promise<(contact: { uid: string; name: string; phone: string }, by: { uid: string; name: string; kind: "helper" | "device" }) => void> {
  const devices = await tx.get(paths.family(db, familyId).collection("devices").select());
  const configs = await Promise.all(devices.docs.map((d) => tx.get(d.ref.collection("config").doc("current"))));
  return (contact, by) => {
    for (const snap of configs) {
      const config = readDeviceConfig(snap.data());
      if (!snap.exists || !config) continue;
      const contactId = familyContactIdFor(contact.uid);
      if (config.family.some((f) => f.id === contactId)) continue;
      const isFirst = !config.family.some((f) => f.isPrimaryHelper);
      tx.update(snap.ref, {
        family: [...config.family, helperFamilyContact(contact.uid, contact.name, contact.phone, isFirst)],
        revision: config.revision + 1,
        updatedBy: by,
        updatedAt: FieldValue.serverTimestamp(),
      });
    }
  };
}
