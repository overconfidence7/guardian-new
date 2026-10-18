import { FieldValue, Timestamp, type DocumentData, type DocumentSnapshot, type Firestore } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { evaluateCheckIn, type CheckInDecision, type CheckInDevice } from "../lib/checkIn.js";
import { db } from "../lib/firebase.js";
import { readCheckIn } from "../lib/model.js";

function dateOf(value: unknown): Date | null {
  return value instanceof Timestamp ? value.toDate() : null;
}

export function checkInDeviceFrom(data: DocumentData | undefined): CheckInDevice {
  const status = (data?.status ?? {}) as Record<string, unknown>;
  return {
    deviceUid: typeof data?.deviceUid === "string" && data.deviceUid ? (data.deviceUid) : null,
    checkIn: data?.checkIn ? readCheckIn(data.checkIn) : null,
    lastHeartbeatAt: dateOf(status.lastHeartbeatAt),
    checkInNotifiedFor: typeof data?.checkInNotifiedFor === "string" ? (data.checkInNotifiedFor) : null,
    silentNotifiedAt: dateOf(data?.silentNotifiedAt),
  };
}

export interface SweepResult {
  checked: number;
  missed: number;
  silent: number;
  failed: number;
}

const CONCURRENCY = 20;

/**
 * Re-evaluates inside a transaction so overlapping sweeps (or a retry) can't send the same
 * notice twice; the event id is derived from what it is about for the same reason.
 */
async function applyDecision(firestore: Firestore, snap: DocumentSnapshot, now: Date): Promise<CheckInDecision["kind"]> {
  return firestore.runTransaction(async (tx) => {
    const fresh = await tx.get(snap.ref);
    if (!fresh.exists) return "none";
    const device = checkInDeviceFrom(fresh.data());
    const decision = evaluateCheckIn(device, now);
    if (decision.kind === "none") return "none";

    const eventRef =
      decision.kind === "missed"
        ? snap.ref.collection("events").doc(`checkin_${decision.localDate}`)
        : snap.ref.collection("events").doc(`silent_${device.lastHeartbeatAt!.getTime()}`);
    const existing = await tx.get(eventRef);

    if (decision.kind === "missed") {
      if (!existing.exists) {
        tx.create(eventRef, {
          type: "CHECK_IN_MISSED",
          severity: "NOTICE",
          at: now.getTime(),
          data: { deadline: decision.deadline, date: decision.localDate },
          createdAt: FieldValue.serverTimestamp(),
          source: "server",
        });
      }
      tx.update(snap.ref, { checkInNotifiedFor: decision.localDate });
    } else {
      if (!existing.exists) {
        tx.create(eventRef, {
          type: "CHECK_IN_MISSED",
          severity: "URGENT",
          at: now.getTime(),
          data: { reason: "silent" },
          createdAt: FieldValue.serverTimestamp(),
          source: "server",
        });
      }
      tx.update(snap.ref, {
        silentNotifiedAt: Timestamp.fromDate(now),
        ...(decision.markDay ? { checkInNotifiedFor: decision.localDate } : {}),
      });
    }
    return decision.kind;
  });
}

export async function runCheckInSweep(firestore: Firestore, now: Date): Promise<SweepResult> {
  const result: SweepResult = { checked: 0, missed: 0, silent: 0, failed: 0 };
  const snaps = await firestore.collectionGroup("devices").where("checkIn.enabled", "==", true).get();
  // Cheap pre-filter outside transactions; most devices need nothing.
  const due = snaps.docs.filter((d) => evaluateCheckIn(checkInDeviceFrom(d.data()), now).kind !== "none");
  result.checked = snaps.size;

  for (let i = 0; i < due.length; i += CONCURRENCY) {
    const outcomes = await Promise.allSettled(due.slice(i, i + CONCURRENCY).map((d) => applyDecision(firestore, d, now)));
    outcomes.forEach((outcome, index) => {
      if (outcome.status === "rejected") {
        result.failed++;
        logger.error("Check-in sweep failed for a device", {
          device: due[i + index]!.ref.path,
          error: String(outcome.reason),
        });
      } else if (outcome.value === "missed") result.missed++;
      else if (outcome.value === "silent") result.silent++;
    });
  }
  return result;
}

export const checkInSweep = onSchedule({ schedule: "every 15 minutes", timeZone: "Etc/UTC", retryCount: 0 }, async () => {
  const result = await runCheckInSweep(db(), new Date());
  logger.info("Check-in sweep done", { ...result });
});
