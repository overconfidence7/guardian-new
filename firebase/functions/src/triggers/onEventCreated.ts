import { Timestamp, type DocumentData, type Firestore } from "firebase-admin/firestore";
import * as logger from "firebase-functions/logger";
import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { notificationCopy, type NotificationCopy } from "../copy.js";
import { paths, stringField } from "../lib/families.js";
import { db } from "../lib/firebase.js";
import { messenger, type Messenger, type SendOutcome } from "../lib/messaging.js";
import { EVENT_RETENTION_MS, isDeliverableEventType, isSeverity, type Severity } from "../lib/model.js";
import { memberStatus } from "../lib/members.js";
import { removeTokens } from "../lib/push.js";

export interface EventRef {
  familyId: string;
  deviceId: string;
  eventId: string;
}

export interface DeliveryResult {
  skipped?: "already-delivered" | "invalid" | "gone";
  deliveredTo: string[];
  removedTokens: number;
}

function wantsPush(notify: unknown, severity: Severity): boolean {
  if (severity === "INFO") return false;
  const prefs = (notify ?? {}) as Record<string, unknown>;
  // URGENT always goes out as push; "off" is only offered for NOTICE.
  if (severity === "URGENT") return true;
  return prefs.notice !== "off";
}

function stringMap(raw: unknown): Record<string, string> {
  if (!raw || typeof raw !== "object") return {};
  return Object.fromEntries(
    Object.entries(raw as Record<string, unknown>).filter((e): e is [string, string] => typeof e[1] === "string"),
  );
}

function tokensOf(member: DocumentData | undefined): string[] {
  const raw: unknown = member?.pushTokens;
  return Array.isArray(raw) ? [...new Set(raw.filter((t): t is string => typeof t === "string" && t.length > 0))] : [];
}

export interface HelperPush {
  copy: NotificationCopy;
  severity: Severity;
  data: Record<string, string>;
  /** Collapses repeats of the same notice. */
  tag: string;
}

/**
 * Pushes one notification to every approved helper of the family (in memberUids and not
 * pending) who wants this severity, and removes tokens FCM says are dead. Returns who got it.
 */
export async function pushToHelpers(
  firestore: Firestore,
  push: Messenger,
  familyId: string,
  message: HelperPush,
): Promise<{ delivered: string[]; removedTokens: number }> {
  const familyDoc = paths.family(firestore, familyId);
  const [familySnap, members] = await Promise.all([familyDoc.get(), familyDoc.collection("members").get()]);
  const memberUids = new Set<string>(
    Array.isArray(familySnap.get("memberUids")) ? (familySnap.get("memberUids") as string[]) : [],
  );
  const link = process.env.DASHBOARD_URL;
  const delivered = new Set<string>();
  let removedTokens = 0;

  await Promise.all(
    members.docs.map(async (member) => {
      const data = member.data();
      if (!memberUids.has(member.id) || memberStatus(data) !== "active") return;
      const tokens = tokensOf(data);
      if (tokens.length === 0 || !wantsPush(data.notify, message.severity)) return;
      let outcomes: SendOutcome[];
      try {
        outcomes = await push.send(tokens, {
          notification: message.copy,
          data: message.data,
          highPriority: message.severity === "URGENT",
          tag: message.tag,
          ...(link ? { link } : {}),
        });
      } catch (error) {
        logger.error("Push send failed", { member: member.id, error: String(error) });
        return;
      }
      if (outcomes.some((o) => o.ok)) delivered.add(member.id);
      const dead = outcomes.filter((o) => o.tokenDead).map((o) => o.token);
      if (dead.length > 0) {
        removedTokens += dead.length;
        await removeTokens(member.ref, dead);
      }
    }),
  );
  return { delivered: [...delivered].sort(), removedTokens };
}

/** Fans an event out to the family's helpers. Safe to run more than once for the same event. */
export async function deliverEvent(
  firestore: Firestore,
  push: Messenger,
  ref: EventRef,
): Promise<DeliveryResult> {
  const eventRef = paths.device(firestore, ref.familyId, ref.deviceId).collection("events").doc(ref.eventId);
  // Read the live document, not the trigger's snapshot: on a retry the snapshot still
  // shows the event as created, while the live copy shows a finished delivery.
  const current = await eventRef.get();
  const event = current.data();
  if (!event) return { skipped: "gone", deliveredTo: [], removedTokens: 0 };
  if (Array.isArray(event.deliveredTo)) {
    return { skipped: "already-delivered", deliveredTo: event.deliveredTo as string[], removedTokens: 0 };
  }
  const createdAt = event.createdAt instanceof Timestamp ? event.createdAt.toMillis() : Date.now();
  const expireAt = Timestamp.fromMillis(createdAt + EVENT_RETENTION_MS);

  const type: unknown = event.type;
  const severity: unknown = event.severity;
  if (!isDeliverableEventType(type) || !isSeverity(severity)) {
    await eventRef.update({ deliveredTo: [], expireAt });
    return { skipped: "invalid", deliveredTo: [], removedTokens: 0 };
  }

  const deviceSnap = await paths.device(firestore, ref.familyId, ref.deviceId).get();
  if (!deviceSnap.exists) return { skipped: "gone", deliveredTo: [], removedTokens: 0 };

  let deliveredTo: string[] = [];
  let removedTokens = 0;
  if (severity !== "INFO") {
    const parentName = stringField(deviceSnap.data(), "parentName");
    const copy = notificationCopy({ type, severity, data: stringMap(event.data) }, { parentName });
    const result = await pushToHelpers(firestore, push, ref.familyId, {
      copy,
      severity,
      data: {
        type: "event",
        eventType: type,
        severity,
        familyId: ref.familyId,
        deviceId: ref.deviceId,
        eventId: ref.eventId,
      },
      tag: ref.eventId,
    });
    deliveredTo = result.delivered;
    removedTokens = result.removedTokens;
  }

  await eventRef.update({ deliveredTo, expireAt });
  return { deliveredTo, removedTokens };
}

export const onEventCreated = onDocumentCreated(
  "families/{familyId}/devices/{deviceId}/events/{eventId}",
  async (event) => {
    const { familyId, deviceId, eventId } = event.params;
    await deliverEvent(db(), messenger(), { familyId, deviceId, eventId });
  },
);
