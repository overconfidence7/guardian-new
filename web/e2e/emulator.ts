/* Helpers for driving the Firebase Emulator Suite from Playwright (project demo-guardian). */
import { initializeApp, getApps, type App } from 'firebase-admin/app';
import { FieldValue, getFirestore } from 'firebase-admin/firestore';

export const PROJECT_ID = 'demo-guardian';
const HOST = process.env.EMULATOR_HOST ?? '127.0.0.1';
export const AUTH_URL = `http://${HOST}:9099`;
export const FIRESTORE_HOST = `${HOST}:8080`;
export const FUNCTIONS_URL = `http://${HOST}:5001/${PROJECT_ID}/us-central1`;

process.env.FIRESTORE_EMULATOR_HOST ??= FIRESTORE_HOST;
process.env.FIREBASE_AUTH_EMULATOR_HOST ??= `${HOST}:9099`;
process.env.GCLOUD_PROJECT ??= PROJECT_ID;

let app: App | undefined;
export function admin() {
  app ??= getApps()[0] ?? initializeApp({ projectId: PROJECT_ID });
  return getFirestore(app);
}

export async function emulatorsUp(): Promise<boolean> {
  try {
    const [a, f] = await Promise.all([fetch(`${AUTH_URL}/`), fetch(`http://${FIRESTORE_HOST}/`)]);
    return a.status < 500 && f.status < 500;
  } catch {
    return false;
  }
}

export async function clearEmulators(): Promise<void> {
  await fetch(`http://${FIRESTORE_HOST}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`, { method: 'DELETE' });
  await fetch(`${AUTH_URL}/emulator/v1/projects/${PROJECT_ID}/accounts`, { method: 'DELETE' });
}

/** Sign up an anonymous user, like the phone does on first launch. Returns its ID token. */
export async function anonymousPhone(): Promise<{ uid: string; idToken: string }> {
  const res = await fetch(`${AUTH_URL}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-api-key`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ returnSecureToken: true }),
  });
  if (!res.ok) throw new Error(`anonymous sign-up failed: ${res.status} ${await res.text()}`);
  const body = (await res.json()) as { localId: string; idToken: string };
  return { uid: body.localId, idToken: body.idToken };
}

/** Call a callable function as the given user (the callable protocol: POST { data }). */
export async function callFunction<T>(name: string, idToken: string, data: unknown): Promise<T> {
  const res = await fetch(`${FUNCTIONS_URL}/${name}`, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${idToken}` },
    body: JSON.stringify({ data }),
  });
  const body = (await res.json()) as { result?: T; error?: { status: string; message: string } };
  if (!res.ok || body.error) throw new Error(`${name} failed: ${res.status} ${JSON.stringify(body.error)}`);
  return body.result as T;
}

/** Write an event the way the phone would (admin write, bypassing rules). */
export async function injectEvent(familyId: string, deviceId: string, event: { type: string; severity: string; data: Record<string, string> }) {
  await admin()
    .collection(`families/${familyId}/devices/${deviceId}/events`)
    .add({ ...event, at: Date.now(), createdAt: FieldValue.serverTimestamp(), source: 'device' });
}

/**
 * Create an email/password helper account with a display name before the test signs in.
 * Signing up through the dashboard's dev sign-in sets the name after the account exists,
 * and the first screen can render before that lands.
 */
export async function createHelperAccount(email: string, password: string, displayName: string): Promise<void> {
  const res = await fetch(`${AUTH_URL}/identitytoolkit.googleapis.com/v1/accounts:signUp?key=demo-api-key`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ email, password, displayName, returnSecureToken: true }),
  });
  if (!res.ok) throw new Error(`helper sign-up failed: ${res.status} ${await res.text()}`);
}
