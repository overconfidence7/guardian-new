/*
 * Brute-force protection for code lookups (docs/SECURITY.md §2). Failed lookups are
 * counted per caller uid and per client IP in fixed one-hour windows; IPs are stored only
 * as a salted hash. The documents live in a server-only collection with a TTL on expireAt.
 */
import { createHash } from "node:crypto";
import { Timestamp, type Firestore } from "firebase-admin/firestore";
import { HttpsError } from "firebase-functions/v2/https";

export const RATE_WINDOW_MS = 60 * 60 * 1000;
export const MAX_FAILURES_PER_UID = 10;
export const MAX_FAILURES_PER_IP = 30;

export interface RateKeys {
  uid: string;
  ip: string | null;
}

interface Bucket {
  key: string;
  limit: number;
}

function hashIp(ip: string): string {
  return createHash("sha256").update(`guardian-mode:${ip}`).digest("hex").slice(0, 32);
}

function bucketsFor(keys: RateKeys): Bucket[] {
  const buckets: Bucket[] = [{ key: `uid_${keys.uid}`, limit: MAX_FAILURES_PER_UID }];
  if (keys.ip) buckets.push({ key: `ip_${hashIp(keys.ip)}`, limit: MAX_FAILURES_PER_IP });
  return buckets;
}

function windowStart(now: number): number {
  return now - (now % RATE_WINDOW_MS);
}

/** Pure: has this bucket used up its allowance in the current window? */
export function isLimited(doc: { windowStart?: number; failures?: number } | undefined, limit: number, now: number): boolean {
  if (doc?.windowStart !== windowStart(now)) return false;
  return (doc.failures ?? 0) >= limit;
}

export async function assertNotRateLimited(db: Firestore, keys: RateKeys, now = Date.now()): Promise<void> {
  const buckets = bucketsFor(keys);
  const snaps = await db.getAll(...buckets.map((b) => db.collection("rateLimits").doc(b.key)));
  snaps.forEach((snap, i) => {
    const data = snap.data() as { windowStart?: number; failures?: number } | undefined;
    if (isLimited(data, buckets[i]!.limit, now)) {
      throw new HttpsError("resource-exhausted", "Too many tries. Please wait a while and try again.", {
        reason: "rate-limited",
      });
    }
  });
}

export async function recordFailure(db: Firestore, keys: RateKeys, now = Date.now()): Promise<void> {
  const start = windowStart(now);
  await Promise.all(
    bucketsFor(keys).map((b) =>
      db.runTransaction(async (tx) => {
        const ref = db.collection("rateLimits").doc(b.key);
        const snap = await tx.get(ref);
        const previous: unknown = snap.get("failures");
        const sameWindow = snap.get("windowStart") === start && typeof previous === "number";
        tx.set(ref, {
          windowStart: start,
          failures: sameWindow ? previous + 1 : 1,
          expireAt: Timestamp.fromMillis(start + 2 * RATE_WINDOW_MS),
        });
      }),
    ),
  );
}
