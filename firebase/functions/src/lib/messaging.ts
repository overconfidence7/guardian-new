/*
 * Push delivery behind a small interface so triggers can be tested without FCM.
 * In the emulator there are no credentials for FCM, so a fake messenger stands in:
 * every token "delivers" except ones starting with "dead-", which behave like tokens
 * FCM reports as unregistered.
 */
import { getMessaging, type MulticastMessage } from "firebase-admin/messaging";
import * as logger from "firebase-functions/logger";
import { adminApp, isEmulator } from "./firebase.js";

export interface PushMessage {
  /** Omitted for data-only messages to the phone. */
  notification?: { title: string; body: string };
  data: Record<string, string>;
  /** High priority on Android, Urgency: high on web push. */
  highPriority: boolean;
  /** Collapses repeats of the same message (e.g. a retried trigger) into one notification. */
  tag?: string;
  /** Web push click-through URL. */
  link?: string;
}

export interface SendOutcome {
  token: string;
  ok: boolean;
  /** FCM says this token will never work again; remove it. */
  tokenDead: boolean;
  error?: string;
}

export interface Messenger {
  send(tokens: readonly string[], message: PushMessage): Promise<SendOutcome[]>;
}

const DEAD_TOKEN_CODES = new Set([
  "messaging/registration-token-not-registered",
  "messaging/invalid-registration-token",
]);

const FCM_BATCH = 500;

/*
 * The contract stores FCM registration tokens (members.pushTokens, devices.fcmToken).
 * firebase-admin 14 deprecates token targeting in favour of Firebase Installation IDs;
 * moving to FIDs is a contract change for the phone and dashboard, so tokens stay for now.
 */
// eslint-disable-next-line @typescript-eslint/no-deprecated -- see above
export function toMulticast(tokens: string[], message: PushMessage): MulticastMessage {
  // eslint-disable-next-line @typescript-eslint/no-deprecated -- see above
  const multicast: MulticastMessage = {
    tokens,
    data: message.data,
    android: {
      priority: message.highPriority ? "high" : "normal",
      ...(message.tag ? { collapseKey: message.tag } : {}),
      ...(message.notification && message.tag ? { notification: { tag: message.tag } } : {}),
    },
    webpush: {
      headers: { Urgency: message.highPriority ? "high" : "normal" },
      ...(message.notification
        ? {
            notification: {
              title: message.notification.title,
              body: message.notification.body,
              ...(message.tag ? { tag: message.tag } : {}),
            },
          }
        : {}),
      ...(message.link ? { fcmOptions: { link: message.link } } : {}),
    },
    apns: {
      headers: { "apns-priority": message.highPriority ? "10" : "5" },
    },
  };
  if (message.notification) multicast.notification = message.notification;
  return multicast;
}

export class FcmMessenger implements Messenger {
  async send(tokens: readonly string[], message: PushMessage): Promise<SendOutcome[]> {
    const outcomes: SendOutcome[] = [];
    const messaging = getMessaging(adminApp());
    for (let i = 0; i < tokens.length; i += FCM_BATCH) {
      const batch = tokens.slice(i, i + FCM_BATCH);
      // eslint-disable-next-line @typescript-eslint/no-deprecated -- token targeting, see toMulticast
      const result = await messaging.sendEachForMulticast(toMulticast(batch, message));
      result.responses.forEach((response, index) => {
        const token = batch[index]!;
        if (response.success) {
          outcomes.push({ token, ok: true, tokenDead: false });
        } else {
          const code = response.error?.code ?? "unknown";
          outcomes.push({ token, ok: false, tokenDead: DEAD_TOKEN_CODES.has(code), error: code });
        }
      });
    }
    return outcomes;
  }
}

export class FakeMessenger implements Messenger {
  readonly sent: { tokens: string[]; message: PushMessage }[] = [];

  send(tokens: readonly string[], message: PushMessage): Promise<SendOutcome[]> {
    this.sent.push({ tokens: [...tokens], message });
    if (tokens.length > 0) {
      logger.info("FakeMessenger: push not sent (emulator)", { tokens: tokens.length, data: message.data });
    }
    return Promise.resolve(
      tokens.map((token) =>
        token.startsWith("dead-")
          ? { token, ok: false, tokenDead: true, error: "messaging/registration-token-not-registered" }
          : { token, ok: true, tokenDead: false },
      ),
    );
  }
}

let override: Messenger | undefined;
let defaultMessenger: Messenger | undefined;

export function messenger(): Messenger {
  if (override) return override;
  defaultMessenger ??= isEmulator() ? new FakeMessenger() : new FcmMessenger();
  return defaultMessenger;
}

/** Tests only. Pass undefined to restore the default. */
export function setMessengerForTests(m: Messenger | undefined): void {
  override = m;
}
