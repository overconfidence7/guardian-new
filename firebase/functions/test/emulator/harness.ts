/*
 * Client and admin handles for tests that run against the Emulator Suite. Every user gets
 * its own client app because Firebase Auth state is per app.
 */
import { deleteApp as deleteAdminApp, initializeApp as initAdmin, type App as AdminApp } from "firebase-admin/app";
import { getFirestore as getAdminFirestore, type Firestore as AdminFirestore } from "firebase-admin/firestore";
import { deleteApp, initializeApp, type FirebaseApp } from "firebase/app";
import {
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  signInAnonymously,
  updateProfile,
  type User,
} from "firebase/auth";
import { connectFirestoreEmulator, getFirestore, type Firestore } from "firebase/firestore";
import { connectFunctionsEmulator, getFunctions, httpsCallable } from "firebase/functions";

export const PROJECT_ID = "demo-guardian";

function hostPort(envVar: string, fallback: string): [string, number] {
  const value = process.env[envVar] ?? fallback;
  const i = value.lastIndexOf(":");
  return [value.slice(0, i), Number(value.slice(i + 1))];
}

export interface Client {
  app: FirebaseApp;
  user: User;
  uid: string;
  db: Firestore;
  call<T = unknown>(name: string, data?: unknown): Promise<T>;
}

const apps: FirebaseApp[] = [];
let counter = 0;

async function client(signIn: (app: FirebaseApp) => Promise<User>): Promise<Client> {
  const app = initializeApp({ projectId: PROJECT_ID, apiKey: "fake-api-key" }, `client-${Date.now()}-${counter++}`);
  apps.push(app);
  const [authHost, authPort] = hostPort("FIREBASE_AUTH_EMULATOR_HOST", "127.0.0.1:9099");
  connectAuthEmulator(getAuth(app), `http://${authHost}:${authPort}`, { disableWarnings: true });
  const [fsHost, fsPort] = hostPort("FIRESTORE_EMULATOR_HOST", "127.0.0.1:8080");
  const db = getFirestore(app);
  connectFirestoreEmulator(db, fsHost, fsPort);
  const functions = getFunctions(app, "us-central1");
  connectFunctionsEmulator(functions, "127.0.0.1", 5001);
  const user = await signIn(app);
  return {
    app,
    user,
    uid: user.uid,
    db,
    call: async <T>(name: string, data?: unknown) => (await httpsCallable(functions, name)(data ?? {})).data as T,
  };
}

/** A helper signed in with email and password (any non-anonymous provider counts as a helper). */
export function helperClient(name: string): Promise<Client> {
  return client(async (app) => {
    const email = `${name.toLowerCase()}-${Date.now()}-${counter}@example.com`;
    const { user } = await createUserWithEmailAndPassword(getAuth(app), email, "correct-horse-battery");
    await updateProfile(user, { displayName: name });
    await user.getIdToken(true);
    return user;
  });
}

/** The parent's phone: an anonymous account. */
export function phoneClient(): Promise<Client> {
  return client(async (app) => (await signInAnonymously(getAuth(app))).user);
}

let adminApp: AdminApp | undefined;

export function admin(): AdminFirestore {
  adminApp ??= initAdmin({ projectId: PROJECT_ID }, "test-admin");
  return getAdminFirestore(adminApp);
}

export async function closeAll(): Promise<void> {
  await Promise.all(apps.splice(0).map((a) => deleteApp(a)));
  if (adminApp) await deleteAdminApp(adminApp);
  adminApp = undefined;
}

/** Polls until `check` returns a value (not undefined) or the timeout passes. */
export async function eventually<T>(check: () => Promise<T | undefined>, timeoutMs = 20_000): Promise<T> {
  const deadline = Date.now() + timeoutMs;
  let last: unknown;
  for (;;) {
    try {
      const value = await check();
      if (value !== undefined) return value;
    } catch (error) {
      last = error;
    }
    if (Date.now() > deadline) {
      throw new Error(`Timed out waiting${last instanceof Error ? `: ${last.message}` : ""}`);
    }
    await new Promise((r) => setTimeout(r, 250));
  }
}

/** The HttpsError code ("functions/failed-precondition" → "failed-precondition") of a rejected call. */
export async function errorCode(promise: Promise<unknown>): Promise<string> {
  try {
    await promise;
  } catch (error) {
    const code = (error as { code?: string }).code ?? "";
    return code.replace(/^functions\//, "");
  }
  return "ok";
}

export async function errorReason(promise: Promise<unknown>): Promise<string | undefined> {
  try {
    await promise;
  } catch (error) {
    const details = (error as { details?: { reason?: string } }).details;
    return details?.reason;
  }
  return undefined;
}
