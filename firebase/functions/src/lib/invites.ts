import { FieldValue, Timestamp, type DocumentSnapshot, type Firestore } from "firebase-admin/firestore";
import { HttpsError, type CallableRequest } from "firebase-functions/v2/https";
import { normalizeInviteCode } from "./codes.js";
import { assertNotRateLimited, recordFailure, type RateKeys } from "./rateLimit.js";

export function rateKeysOf(request: Pick<CallableRequest, "rawRequest">, uid: string): RateKeys {
  const ip = (request.rawRequest as { ip?: unknown } | undefined)?.ip;
  return { uid, ip: typeof ip === "string" && ip.length > 0 ? ip : null };
}

export interface Invite {
  code: string;
  familyId: string;
  deviceId: string | null;
  kind: "device" | "helper";
  createdBy: string;
  createdByName: string;
  expiresAt: Date;
  usedBy: string | null;
  failedAttempts: number;
}

/** Wrong tries against one code before it stops working (docs/SECURITY.md §2). */
export const MAX_FAILED_ATTEMPTS = 5;

export function normalizedCodeOrThrow(input: string): string {
  const code = normalizeInviteCode(input);
  if (!code) throw new HttpsError("invalid-argument", "Setup codes are 6 letters and numbers.", { reason: "bad-code" });
  return code;
}

export function readInvite(snap: DocumentSnapshot): Invite {
  const d = snap.data();
  if (!snap.exists || !d) {
    throw new HttpsError("not-found", "We couldn't find that code. Please check it and try again.", {
      reason: "unknown-code",
    });
  }
  const expires: unknown = d.expiresAt;
  return {
    code: snap.id,
    familyId: String(d.familyId ?? ""),
    deviceId: typeof d.deviceId === "string" ? d.deviceId : null,
    kind: d.kind === "helper" ? "helper" : "device",
    createdBy: String(d.createdBy ?? ""),
    createdByName: String(d.createdByName ?? ""),
    expiresAt: expires instanceof Timestamp ? expires.toDate() : new Date(0),
    usedBy: typeof d.usedBy === "string" ? d.usedBy : null,
    failedAttempts: typeof d.failedAttempts === "number" ? d.failedAttempts : 0,
  };
}

/**
 * Throws unless `uid` may use the invite now. Re-using a code you already used is allowed
 * so that a retried request (lost response, app restart) succeeds instead of failing.
 */
export function assertUsable(invite: Invite, kind: Invite["kind"], uid: string, now: Date): void {
  if (invite.failedAttempts >= MAX_FAILED_ATTEMPTS) {
    throw new HttpsError("failed-precondition", "This code has been locked after too many tries. Ask for a new one.", {
      reason: "locked",
    });
  }
  if (invite.kind !== kind) {
    throw new HttpsError(
      "failed-precondition",
      kind === "device" ? "This code is for adding a helper, not a phone." : "This code is for setting up a phone.",
      { reason: "wrong-kind" },
    );
  }
  if (invite.usedBy === uid) return;
  if (invite.usedBy !== null) {
    throw new HttpsError("failed-precondition", "This code has already been used. Ask for a new one.", { reason: "used" });
  }
  if (invite.expiresAt.getTime() <= now.getTime()) {
    throw new HttpsError("failed-precondition", "This code has expired. Ask for a new one.", { reason: "expired" });
  }
}

const CODE_FAILURES = new Set(["bad-code", "unknown-code", "wrong-kind", "used", "expired", "locked"]);

function failureReason(error: unknown): string | null {
  if (!(error instanceof HttpsError)) return null;
  const details: unknown = error.details;
  const reason = details && typeof details === "object" ? (details as { reason?: unknown }).reason : undefined;
  return typeof reason === "string" && CODE_FAILURES.has(reason) ? reason : null;
}

/**
 * Runs a code lookup with brute-force protection: callers over their failure allowance are
 * refused up front, and every failed lookup counts against the caller (uid and IP) and,
 * when the code exists, against the code itself until it locks.
 */
export async function guardCodeLookup<T>(
  db: Firestore,
  keys: RateKeys,
  rawCode: string,
  lookup: (code: string) => Promise<T>,
): Promise<T> {
  await assertNotRateLimited(db, keys);
  const code = normalizeInviteCode(rawCode);
  try {
    return await lookup(normalizedCodeOrThrow(rawCode));
  } catch (error) {
    const reason = failureReason(error);
    if (reason) {
      await recordFailure(db, keys);
      if (code && reason !== "unknown-code") {
        await db
          .collection("invites")
          .doc(code)
          .update({ failedAttempts: FieldValue.increment(1) })
          .catch(() => undefined);
      }
    }
    throw error;
  }
}
