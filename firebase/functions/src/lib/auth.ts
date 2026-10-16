import { HttpsError, type CallableRequest } from "firebase-functions/v2/https";
import type { ZodType } from "zod";

export interface Caller {
  uid: string;
  anonymous: boolean;
  name: string | null;
  email: string | null;
  picture: string | null;
}

export function callerOf(request: Pick<CallableRequest, "auth">): Caller {
  const auth = request.auth;
  if (!auth) throw new HttpsError("unauthenticated", "Please sign in first.");
  const token = auth.token;
  const str = (v: unknown): string | null => (typeof v === "string" && v.length > 0 ? v : null);
  return {
    uid: auth.uid,
    anonymous: token.firebase.sign_in_provider === "anonymous",
    name: str(token.name),
    email: str(token.email),
    picture: str(token.picture),
  };
}

/** Helpers sign in with Google or an email link; the phone's anonymous account can't act as one. */
export function requireHelper(request: Pick<CallableRequest, "auth">): Caller {
  const caller = callerOf(request);
  if (caller.anonymous) {
    throw new HttpsError("permission-denied", "Helpers need to sign in with an account.");
  }
  return caller;
}

/** The phone signs in anonymously on first launch. */
export function requirePhone(request: Pick<CallableRequest, "auth">): Caller {
  const caller = callerOf(request);
  if (!caller.anonymous) {
    throw new HttpsError("permission-denied", "Setup codes are entered on the parent's phone.");
  }
  return caller;
}

export function parseInput<T>(schema: ZodType<T>, data: unknown): T {
  const result = schema.safeParse(data ?? {});
  if (!result.success) {
    const issue = result.error.issues[0];
    const where = issue?.path.length ? `${issue.path.join(".")}: ` : "";
    throw new HttpsError("invalid-argument", `${where}${issue?.message ?? "Invalid input."}`);
  }
  return result.data;
}

export function displayNameFor(caller: Caller): string {
  if (caller.name) return caller.name;
  if (caller.email) return caller.email.split("@")[0]!;
  return "Helper";
}
