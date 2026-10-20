/* DataApi backed by the real Firebase project or the local Emulator Suite. */
import { initializeApp, type FirebaseApp } from 'firebase/app';
import {
  GoogleAuthProvider,
  connectAuthEmulator,
  createUserWithEmailAndPassword,
  getAuth,
  isSignInWithEmailLink,
  onIdTokenChanged,
  sendSignInLinkToEmail,
  signInWithEmailAndPassword,
  signInWithEmailLink,
  signInWithPopup,
  signInWithRedirect,
  signOut,
  type Auth,
  type User,
} from 'firebase/auth';
import {
  arrayRemove,
  arrayUnion,
  collection,
  connectFirestoreEmulator,
  doc,
  initializeFirestore,
  limit,
  memoryLocalCache,
  onSnapshot,
  orderBy,
  persistentLocalCache,
  persistentMultipleTabManager,
  query,
  runTransaction,
  serverTimestamp,
  setDoc,
  deleteDoc,
  updateDoc,
  where,
  type DocumentData,
  type DocumentSnapshot,
  type Firestore,
  type QueryDocumentSnapshot,
  type SnapshotOptions,
} from 'firebase/firestore';
import { connectFunctionsEmulator, getFunctions, httpsCallable, type Functions } from 'firebase/functions';
import { connectStorageEmulator, deleteObject, getDownloadURL, getStorage, ref, uploadBytes, type FirebaseStorage } from 'firebase/storage';
import { emulatorHost, firebaseConfig, FUNCTIONS_REGION, useEmulators } from '../lib/env';
import { newId } from '../lib/ids';
import { stripUndefined, threeWayMerge } from '../lib/merge';
import { splitRiskyChanges } from '../lib/riskyChanges';
import { parseDeviceConfig, type AnyEventType, type DeviceConfig, type Severity } from '../lib/model';
import type { ChangeDoc, ConfigDoc, Device, EventDoc, Family, Invite, Member, PhotoDoc } from '../lib/types';
import type { AppUser, AuthApi, DataApi, ErrorListener } from './api';

const EMAIL_KEY = 'gm.emailForSignIn';
const SNAP: SnapshotOptions = { serverTimestamps: 'estimate' };

interface Services {
  app: FirebaseApp;
  auth: Auth;
  db: Firestore;
  storage: FirebaseStorage;
  functions: Functions;
}

let services: Services | null = null;

export function getServices(): Services {
  if (services) return services;
  const app = initializeApp(firebaseConfig);
  const auth = getAuth(app);
  const db = initializeFirestore(app, {
    ignoreUndefinedProperties: true,
    localCache: useEmulators
      ? memoryLocalCache()
      : persistentLocalCache({ tabManager: persistentMultipleTabManager() }),
  });
  const storage = getStorage(app);
  const functions = getFunctions(app, FUNCTIONS_REGION);
  if (useEmulators) {
    connectAuthEmulator(auth, `http://${emulatorHost}:9099`, { disableWarnings: true });
    connectFirestoreEmulator(db, emulatorHost, 8080);
    connectStorageEmulator(storage, emulatorHost, 9199);
    connectFunctionsEmulator(functions, emulatorHost, 5001);
  }
  services = { app, auth, db, storage, functions };
  return services;
}

/* ---------- converters ---------- */

function millis(v: unknown): number | null {
  if (v == null) return null;
  if (typeof v === 'number') return v;
  if (typeof (v as { toMillis?: () => number }).toMillis === 'function') return (v as { toMillis: () => number }).toMillis();
  return null;
}

const str = (v: unknown, fallback = ''): string => (typeof v === 'string' ? v : fallback);
const strArr = (v: unknown): string[] => (Array.isArray(v) ? v.filter((x): x is string => typeof x === 'string') : []);

function toFamily(s: DocumentSnapshot<DocumentData>): Family | null {
  const d = s.data(SNAP);
  if (!d) return null;
  return {
    id: s.id,
    name: str(d.name, 'Our family'),
    plan: d.plan === 'family' ? 'family' : 'free',
    ownerUid: str(d.ownerUid),
    memberUids: strArr(d.memberUids),
    createdAt: millis(d.createdAt),
  };
}

function toMember(s: QueryDocumentSnapshot<DocumentData>): Member {
  const d = s.data(SNAP);
  return {
    uid: s.id,
    role: d.role === 'owner' ? 'owner' : 'helper',
    displayName: str(d.displayName, 'Helper'),
    email: str(d.email),
    photoUrl: typeof d.photoUrl === 'string' ? d.photoUrl : null,
    phone: str(d.phone),
    pushTokens: strArr(d.pushTokens),
    notify: { urgent: 'push', notice: d.notify?.notice === 'off' ? 'off' : 'push' },
    status: d.status === 'pending' ? 'pending' : 'active',
    joinedAt: millis(d.joinedAt),
  };
}

function toDevice(s: DocumentSnapshot<DocumentData>): Device | null {
  const d = s.data(SNAP);
  if (!d) return null;
  return {
    id: s.id,
    parentName: str(d.parentName, 'Mom'),
    deviceUid: typeof d.deviceUid === 'string' && d.deviceUid ? d.deviceUid : null,
    model: str(d.model),
    androidVersion: str(d.androidVersion),
    appVersion: str(d.appVersion),
    status: d.status
      ? {
          protected: typeof d.status.protected === 'boolean' ? d.status.protected : null,
          missing: strArr(d.status.missing),
          lastHeartbeatAt: millis(d.status.lastHeartbeatAt),
          lastSeenAt: millis(d.status.lastSeenAt),
        }
      : null,
    installedApps: Array.isArray(d.installedApps)
      ? d.installedApps
          .filter((a: unknown): a is { packageName: string; label?: string } => typeof (a as { packageName?: unknown })?.packageName === 'string')
          .map((a: { packageName: string; label?: string }) => ({ packageName: a.packageName, label: str(a.label, a.packageName) }))
      : [],
    checkIn: d.checkIn ?? null,
    consent: d.consent
      ? { version: Number(d.consent.version ?? 0), acceptedAt: millis(d.consent.acceptedAt), features: d.consent.features ?? {} }
      : null,
    createdAt: millis(d.createdAt),
  };
}

function toEvent(s: QueryDocumentSnapshot<DocumentData>): EventDoc {
  const d = s.data(SNAP);
  const data: Record<string, string> = {};
  if (d.data && typeof d.data === 'object') {
    for (const [k, v] of Object.entries(d.data as Record<string, unknown>)) data[k] = String(v);
  }
  return {
    id: s.id,
    type: d.type as AnyEventType,
    severity: (d.severity as Severity) ?? 'NOTICE',
    at: typeof d.at === 'number' ? d.at : (millis(d.createdAt) ?? Date.now()),
    data,
    createdAt: millis(d.createdAt),
    source: d.source === 'server' ? 'server' : 'device',
    deliveredTo: strArr(d.deliveredTo),
  };
}

function toPhoto(s: QueryDocumentSnapshot<DocumentData>): PhotoDoc {
  const d = s.data(SNAP);
  return {
    id: s.id,
    storagePath: str(d.storagePath),
    fromUid: str(d.fromUid),
    fromName: str(d.fromName),
    caption: str(d.caption),
    createdAt: millis(d.createdAt),
  };
}

function toChange(s: QueryDocumentSnapshot<DocumentData>): ChangeDoc {
  const d = s.data(SNAP);
  return { id: s.id, byUid: str(d.byUid), byName: str(d.byName), summary: str(d.summary), at: millis(d.at) };
}

export function toInvite(raw: unknown): Invite {
  const r = raw as { code: string; expiresAt: unknown };
  const e = r.expiresAt;
  let expiresAt = Number.NaN;
  if (typeof e === 'number') expiresAt = e;
  else if (typeof e === 'string') expiresAt = Date.parse(e);
  else if (e && typeof e === 'object') {
    // A Timestamp serialized by a callable: { _seconds, _nanoseconds } or { seconds, nanoseconds }.
    const o = e as { seconds?: unknown; _seconds?: unknown };
    const sec = typeof o.seconds === 'number' ? o.seconds : typeof o._seconds === 'number' ? o._seconds : Number.NaN;
    expiresAt = sec * 1000;
  }
  return { code: r.code, expiresAt: Number.isFinite(expiresAt) ? expiresAt : Date.now() + 24 * 3600_000 };
}

const err = (onError?: ErrorListener) => (e: Error) => {
  console.error(e);
  onError?.(e);
};

/* ---------- auth ---------- */

function toUser(u: User): AppUser {
  return {
    uid: u.uid,
    displayName: u.displayName || (u.email ? u.email.split('@')[0]! : 'Helper'),
    email: u.email ?? '',
    photoUrl: u.photoURL,
  };
}

const isStandalone = () =>
  typeof window !== 'undefined' &&
  (window.matchMedia?.('(display-mode: standalone)').matches || (navigator as { standalone?: boolean }).standalone === true);

const authApi: AuthApi = {
  onUser(cb) {
    const { auth } = getServices();
    // Token changes, not just sign-in changes, so a name set right after sign-up (devSignIn) reaches the UI.
    // Hourly token refreshes are dropped unless something the UI shows changed.
    let last: string | undefined;
    return onIdTokenChanged(auth, (u) => {
      const user = u && !u.isAnonymous ? toUser(u) : null;
      const key = JSON.stringify(user);
      if (key === last) return;
      last = key;
      cb(user);
    });
  },
  async signInWithGoogle() {
    const { auth } = getServices();
    const provider = new GoogleAuthProvider();
    provider.setCustomParameters({ prompt: 'select_account' });
    // Popups don't work in an iOS home-screen app; use a redirect there.
    if (isStandalone()) return signInWithRedirect(auth, provider);
    try {
      await signInWithPopup(auth, provider);
    } catch (e) {
      if ((e as { code?: string }).code === 'auth/popup-blocked') return signInWithRedirect(auth, provider);
      throw e;
    }
  },
  async sendEmailLink(email, continuePath = '/') {
    const { auth } = getServices();
    const url = new URL('/finish-sign-in', window.location.origin);
    url.searchParams.set('next', continuePath);
    await sendSignInLinkToEmail(auth, email, { url: url.toString(), handleCodeInApp: true });
    try {
      window.localStorage.setItem(EMAIL_KEY, email);
    } catch {
      /* private mode: we'll ask for the email again */
    }
  },
  async completeEmailLink(emailIfNeeded) {
    const { auth } = getServices();
    const href = window.location.href;
    if (!isSignInWithEmailLink(auth, href)) return 'not-a-link';
    let email = emailIfNeeded;
    try {
      email ||= window.localStorage.getItem(EMAIL_KEY) ?? undefined;
    } catch {
      /* ignore */
    }
    if (!email) return 'need-email';
    await signInWithEmailLink(auth, email, href);
    try {
      window.localStorage.removeItem(EMAIL_KEY);
    } catch {
      /* ignore */
    }
    return 'done';
  },
  devSignIn: useEmulators
    ? async (email, password) => {
        const { auth } = getServices();
        try {
          await signInWithEmailAndPassword(auth, email, password);
        } catch (e) {
          const code = (e as { code?: string }).code;
          if (code === 'auth/user-not-found' || code === 'auth/invalid-credential' || code === 'auth/invalid-login-credentials') {
            const cred = await createUserWithEmailAndPassword(auth, email, password);
            const { updateProfile } = await import('firebase/auth');
            const name = email.split('@')[0] ?? 'Helper';
            await updateProfile(cred.user, { displayName: name.charAt(0).toUpperCase() + name.slice(1) });
            await cred.user.reload();
            // Fires onIdTokenChanged again with the name, which onAuthStateChanged never would.
            await cred.user.getIdToken(true);
          } else {
            throw e;
          }
        }
      }
    : undefined,
  async signOut() {
    await signOut(getServices().auth);
  },
};

/* ---------- data ---------- */

const familyPath = (f: string) => `families/${f}`;
const devicePath = (f: string, d: string) => `families/${f}/devices/${d}`;

async function call<I, O>(name: string, input: I): Promise<O> {
  const fn = httpsCallable<I, O>(getServices().functions, name);
  const res = await fn(input);
  return res.data;
}

export const firebaseApi: DataApi = {
  mode: 'firebase',
  auth: authApi,

  watchMyFamilies(uid, cb, onError) {
    const { db } = getServices();
    const q = query(collection(db, 'families'), where('memberUids', 'array-contains', uid));
    return onSnapshot(q, (snap) => cb(snap.docs.map((d) => toFamily(d)).filter((f): f is Family => !!f)), err(onError));
  },
  watchFamily(familyId, cb, onError) {
    return onSnapshot(doc(getServices().db, familyPath(familyId)), (s) => cb(toFamily(s)), err(onError));
  },
  watchMyMembership(familyId, uid, cb) {
    // Two signals, because a pending helper may not be allowed to read the family yet:
    //  1. families where memberUids contains me (rules allow it once I'm approved);
    //  2. my own member doc (status), where the rules allow reading it.
    const { db } = getServices();
    let inFamily = false;
    let memberStatus: 'pending' | 'active' | 'missing' | 'unknown' = 'unknown';
    let sawMember = false;
    const emit = () => {
      if (inFamily || memberStatus === 'active') cb('active');
      else if (memberStatus === 'missing' && sawMember) cb('declined');
      else cb('pending');
    };
    const q = query(collection(db, 'families'), where('memberUids', 'array-contains', uid));
    const u1 = onSnapshot(
      q,
      (snap) => {
        inFamily = snap.docs.some((d) => d.id === familyId);
        emit();
      },
      () => emit(),
    );
    const u2 = onSnapshot(
      doc(db, familyPath(familyId), 'members', uid),
      (s) => {
        if (s.exists()) {
          sawMember = true;
          memberStatus = s.data()?.status === 'pending' ? 'pending' : 'active';
        } else memberStatus = 'missing';
        emit();
      },
      () => {
        memberStatus = 'unknown';
        emit();
      },
    );
    return () => {
      u1();
      u2();
    };
  },
  watchMembers(familyId, cb, onError) {
    return onSnapshot(
      collection(getServices().db, familyPath(familyId), 'members'),
      (snap) => cb(snap.docs.map(toMember).sort((a, b) => (a.role === 'owner' ? -1 : b.role === 'owner' ? 1 : (a.joinedAt ?? 0) - (b.joinedAt ?? 0)))),
      err(onError),
    );
  },
  watchDevices(familyId, cb, onError) {
    return onSnapshot(
      collection(getServices().db, familyPath(familyId), 'devices'),
      (snap) =>
        cb(
          snap.docs
            .map((d) => toDevice(d))
            .filter((d): d is Device => !!d)
            .sort((a, b) => (a.createdAt ?? 0) - (b.createdAt ?? 0)),
        ),
      err(onError),
    );
  },
  watchDevice(familyId, deviceId, cb, onError) {
    return onSnapshot(doc(getServices().db, devicePath(familyId, deviceId)), (s) => cb(toDevice(s)), err(onError));
  },
  watchConfig(familyId, deviceId, cb, onError) {
    return onSnapshot(
      doc(getServices().db, devicePath(familyId, deviceId), 'config', 'current'),
      (s) => {
        const d = s.data(SNAP);
        const doc: ConfigDoc = {
          config: parseDeviceConfig(d ?? {}),
          updatedBy: d?.updatedBy ?? null,
          updatedAt: millis(d?.updatedAt),
          exists: s.exists(),
        };
        cb(doc);
      },
      err(onError),
    );
  },
  watchEvents(familyId, deviceId, max, cb, onError) {
    const q = query(collection(getServices().db, devicePath(familyId, deviceId), 'events'), orderBy('createdAt', 'desc'), limit(max));
    return onSnapshot(q, (snap) => cb(snap.docs.map(toEvent)), err(onError));
  },
  watchPhotos(familyId, deviceId, cb, onError) {
    const q = query(collection(getServices().db, devicePath(familyId, deviceId), 'photos'), orderBy('createdAt', 'desc'), limit(60));
    return onSnapshot(q, (snap) => cb(snap.docs.map(toPhoto)), err(onError));
  },
  watchChanges(familyId, deviceId, max, cb, onError) {
    const q = query(collection(getServices().db, devicePath(familyId, deviceId), 'changes'), orderBy('at', 'desc'), limit(max));
    return onSnapshot(q, (snap) => cb(snap.docs.map(toChange)), err(onError));
  },

  fileUrl(storagePath) {
    return getDownloadURL(ref(getServices().storage, storagePath));
  },

  async saveConfig(familyId, deviceId, base, draft, by) {
    const { db } = getServices();
    const configRef = doc(db, devicePath(familyId, deviceId), 'config', 'current');
    const deviceRef = doc(db, devicePath(familyId, deviceId));
    return runTransaction(db, async (tx) => {
      const [snap, deviceSnap] = await Promise.all([tx.get(configRef), tx.get(deviceRef)]);
      const remote = parseDeviceConfig(snap.data() ?? {});
      const merged = threeWayMerge(base, draft, remote);
      // With a phone paired, the rules hold risky changes for the parent (SECURITY.md §3).
      const paired = typeof deviceSnap.get('deviceUid') === 'string';
      const { direct, request } = paired ? splitRiskyChanges(remote, merged) : { direct: merged, request: null };
      const written: DeviceConfig = { ...direct, revision: Date.now() };
      tx.set(
        configRef,
        stripUndefined({
          ...written,
          updatedBy: { uid: by.uid, name: by.name, kind: 'helper' as const },
          updatedAt: serverTimestamp(),
        }),
      );
      if (request) {
        tx.set(
          doc(collection(db, devicePath(familyId, deviceId), 'requests')),
          stripUndefined({ byUid: by.uid, byName: by.name, changes: request, status: 'pending', createdAt: serverTimestamp() }),
        );
      }
      return { config: written, held: request };
    });
  },

  async uploadPhoto(familyId, deviceId, jpeg, caption, by) {
    const { db, storage } = getServices();
    const photoId = newId('p');
    const storagePath = `${devicePath(familyId, deviceId)}/photos/${photoId}.jpg`;
    await uploadBytes(ref(storage, storagePath), jpeg, {
      contentType: 'image/jpeg',
      customMetadata: { fromUid: by.uid },
    });
    await setDoc(doc(db, devicePath(familyId, deviceId), 'photos', photoId), {
      storagePath,
      fromUid: by.uid,
      fromName: by.name,
      caption: caption.trim(),
      createdAt: serverTimestamp(),
    });
  },
  async deletePhoto(familyId, deviceId, photo) {
    const { db, storage } = getServices();
    await deleteDoc(doc(db, devicePath(familyId, deviceId), 'photos', photo.id));
    try {
      await deleteObject(ref(storage, photo.storagePath));
    } catch (e) {
      if ((e as { code?: string }).code !== 'storage/object-not-found') console.warn('Photo file not removed', e);
    }
  },
  async uploadContactPhoto(familyId, deviceId, jpeg) {
    // Storage rules only open families/{f}/devices/{d}/photos/{id}.jpg to helpers, so
    // contact pictures live there too, without a photos/ doc (they never reach the photo card).
    const { storage } = getServices();
    const path = `${devicePath(familyId, deviceId)}/photos/${newId('contact')}.jpg`;
    const r = ref(storage, path);
    await uploadBytes(r, jpeg, { contentType: 'image/jpeg', customMetadata: { purpose: 'contact' } });
    return getDownloadURL(r);
  },

  async updateMyMember(familyId, uid, patch) {
    await updateDoc(doc(getServices().db, familyPath(familyId), 'members', uid), stripUndefined(patch) as DocumentData);
  },
  async addPushToken(familyId, uid, token) {
    await updateDoc(doc(getServices().db, familyPath(familyId), 'members', uid), { pushTokens: arrayUnion(token) });
  },
  async removePushToken(familyId, uid, token) {
    await updateDoc(doc(getServices().db, familyPath(familyId), 'members', uid), { pushTokens: arrayRemove(token) });
  },
  async renameDevice(familyId, deviceId, parentName) {
    await updateDoc(doc(getServices().db, devicePath(familyId, deviceId)), { parentName: parentName.trim() });
  },

  createFamily: (input) => call('createFamily', input),
  addDevice: (input) => call('addDevice', input),
  createDeviceInvite: async (input) => toInvite(await call('createDeviceInvite', input)),
  createHelperInvite: async (input) => toInvite(await call('createHelperInvite', input)),
  acceptHelperInvite: (input) => call('acceptHelperInvite', input),
  removeHelper: async (input) => {
    await call('removeHelper', input);
  },
  deleteDevice: async (input) => {
    await call('deleteDevice', input);
  },
  deleteFamily: async (input) => {
    await call('deleteFamily', input);
  },
};

export default firebaseApi;
