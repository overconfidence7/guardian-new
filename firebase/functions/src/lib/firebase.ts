import { getApps, initializeApp, type App } from "firebase-admin/app";
import { getFirestore, type Firestore } from "firebase-admin/firestore";
import { getStorage } from "firebase-admin/storage";

let app: App | undefined;

export function adminApp(): App {
  app ??= getApps()[0] ?? initializeApp();
  return app;
}

export function db(): Firestore {
  return getFirestore(adminApp());
}

export function bucket(): ReturnType<ReturnType<typeof getStorage>["bucket"]> {
  return getStorage(adminApp()).bucket();
}

export function isEmulator(): boolean {
  return process.env.FUNCTIONS_EMULATOR === "true";
}
