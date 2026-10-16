import { randomInt } from "node:crypto";
import type { DocumentReference, Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";
import { INVITE_ALPHABET, INVITE_CODE_LENGTH } from "./model.js";

/** Random index source; crypto.randomInt by default (uniform, no modulo bias). */
export type RandomIndex = (maxExclusive: number) => number;

export function generateInviteCode(random: RandomIndex = randomInt): string {
  let code = "";
  for (let i = 0; i < INVITE_CODE_LENGTH; i++) {
    code += INVITE_ALPHABET.charAt(random(INVITE_ALPHABET.length));
  }
  return code;
}

const CODE_RE = new RegExp(`^[${INVITE_ALPHABET}]{${INVITE_CODE_LENGTH}}$`);

/** Accepts what a person might type: lower case, spaces or a dash in the middle. */
export function normalizeInviteCode(input: string): string | null {
  const code = input.replace(/[\s-]/g, "").toUpperCase();
  return CODE_RE.test(code) ? code : null;
}

/** gRPC ALREADY_EXISTS, as raised by DocumentReference.create(). */
function isAlreadyExists(error: unknown): boolean {
  return typeof error === "object" && error !== null && "code" in error && (error).code === 6;
}

export const MAX_CODE_ATTEMPTS = 8;

/**
 * Stores `data` under a fresh code at invites/{code}. `create()` fails atomically if the
 * code is already taken (live or expired), in which case another code is drawn.
 */
export async function allocateInviteCode(
  firestore: Firestore,
  data: Record<string, unknown>,
  random: RandomIndex = randomInt,
): Promise<{ code: string; ref: DocumentReference }> {
  for (let attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
    const code = generateInviteCode(random);
    const ref = firestore.collection("invites").doc(code);
    try {
      await ref.create(data);
      return { code, ref };
    } catch (error) {
      if (!isAlreadyExists(error)) throw error;
    }
  }
  throw new HttpsError("unavailable", "Couldn't make a new code right now. Please try again.");
}
