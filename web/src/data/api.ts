/*
 * The dashboard talks to one interface. `firebaseApi` implements it against Firestore,
 * Storage and the callable functions in docs/API.md; `demoApi` implements it in memory
 * with fixture data for /demo (no backend) and for screenshots.
 */
import type { DeviceConfig } from '../lib/model';
import type { ChangeDoc, ConfigDoc, Device, EventDoc, Family, Invite, Member, PhotoDoc } from '../lib/types';

export type Unsubscribe = () => void;
export type Listener<T> = (value: T) => void;
export type ErrorListener = (error: Error) => void;

export interface AppUser {
  uid: string;
  displayName: string;
  email: string;
  photoUrl: string | null;
}

export interface Author {
  uid: string;
  name: string;
}

export type MembershipState = 'pending' | 'active' | 'declined';

export interface AuthApi {
  /** Calls back with the signed-in user or null; first call happens once state is known. */
  onUser(cb: Listener<AppUser | null>): Unsubscribe;
  signInWithGoogle(): Promise<void>;
  sendEmailLink(email: string, continuePath?: string): Promise<void>;
  /** If the current URL is an email sign-in link, finish it. Returns true when handled. */
  completeEmailLink(emailIfNeeded?: string): Promise<'done' | 'need-email' | 'not-a-link'>;
  /** Emulator-only email + password sign-in (creates the user if needed). */
  devSignIn?(email: string, password: string): Promise<void>;
  signOut(): Promise<void>;
}

export interface DataApi {
  readonly mode: 'firebase' | 'demo';
  readonly auth: AuthApi;

  watchMyFamilies(uid: string, cb: Listener<Family[]>, onError?: ErrorListener): Unsubscribe;
  watchFamily(familyId: string, cb: Listener<Family | null>, onError?: ErrorListener): Unsubscribe;
  /**
   * The signed-in helper's own membership while it waits for the parent's approval:
   * 'active' once approved, 'declined' if the member doc disappears, else 'pending'.
   */
  watchMyMembership(familyId: string, uid: string, cb: Listener<MembershipState>): Unsubscribe;
  watchMembers(familyId: string, cb: Listener<Member[]>, onError?: ErrorListener): Unsubscribe;
  watchDevices(familyId: string, cb: Listener<Device[]>, onError?: ErrorListener): Unsubscribe;
  watchDevice(familyId: string, deviceId: string, cb: Listener<Device | null>, onError?: ErrorListener): Unsubscribe;
  watchConfig(familyId: string, deviceId: string, cb: Listener<ConfigDoc>, onError?: ErrorListener): Unsubscribe;
  watchEvents(familyId: string, deviceId: string, max: number, cb: Listener<EventDoc[]>, onError?: ErrorListener): Unsubscribe;
  watchPhotos(familyId: string, deviceId: string, cb: Listener<PhotoDoc[]>, onError?: ErrorListener): Unsubscribe;
  watchChanges(familyId: string, deviceId: string, max: number, cb: Listener<ChangeDoc[]>, onError?: ErrorListener): Unsubscribe;

  /** Download URL for a Storage path (photos, help screenshots). */
  fileUrl(storagePath: string): Promise<string>;

  /**
   * Write the whole config doc. Merges `draft` onto the latest document using `base`
   * (what the editor started from) so concurrent edits elsewhere are kept.
   * Returns the config that was written.
   */
  saveConfig(familyId: string, deviceId: string, base: DeviceConfig, draft: DeviceConfig, by: Author): Promise<DeviceConfig>;

  uploadPhoto(familyId: string, deviceId: string, jpeg: Blob, caption: string, by: Author): Promise<void>;
  deletePhoto(familyId: string, deviceId: string, photo: PhotoDoc): Promise<void>;
  /** Upload a contact picture; returns the URL to store in `photoUrl`. */
  uploadContactPhoto(familyId: string, deviceId: string, jpeg: Blob): Promise<string>;

  updateMyMember(familyId: string, uid: string, patch: Partial<Pick<Member, 'displayName' | 'phone' | 'notify'>>): Promise<void>;
  addPushToken(familyId: string, uid: string, token: string): Promise<void>;
  removePushToken(familyId: string, uid: string, token: string): Promise<void>;
  renameDevice(familyId: string, deviceId: string, parentName: string): Promise<void>;

  createFamily(input: { familyName: string; parentName: string; helperPhone?: string }): Promise<{ familyId: string; deviceId: string }>;
  addDevice(input: { familyId: string; parentName: string }): Promise<{ deviceId: string }>;
  createDeviceInvite(input: { familyId: string; deviceId: string }): Promise<Invite>;
  createHelperInvite(input: { familyId: string }): Promise<Invite>;
  acceptHelperInvite(input: { code: string; displayName: string; phone?: string }): Promise<{ familyId: string; status: 'pending' | 'active' }>;
  removeHelper(input: { familyId: string; uid: string }): Promise<void>;
  deleteDevice(input: { familyId: string; deviceId: string }): Promise<void>;
  deleteFamily(input: { familyId: string }): Promise<void>;
}

/** Errors from callables carry a code; screens turn them into friendly words. */
export function friendlyError(error: unknown): string {
  const code = (error as { code?: string } | null)?.code ?? '';
  const short = code.replace(/^functions\//, '').replace(/^firestore\//, '').replace(/^storage\//, '').replace(/^auth\//, '');
  switch (short) {
    case 'unauthenticated':
      return 'Please sign in again.';
    case 'permission-denied':
    case 'unauthorized':
      return 'You don’t have permission to do that in this family.';
    case 'not-found':
      return 'We couldn’t find that. The code may be mistyped.';
    case 'failed-precondition':
      return (error as Error).message && !/^failed-precondition$/i.test((error as Error).message)
        ? (error as Error).message
        : 'That code has expired or was already used. Ask for a new one.';
    case 'invalid-argument':
      return 'Something in the form isn’t filled in right. Check it and try again.';
    case 'unavailable':
    case 'network-request-failed':
      return 'You seem to be offline. Your changes are saved here, so try again in a minute.';
    case 'popup-closed-by-user':
    case 'cancelled-popup-request':
      return 'Sign-in was cancelled.';
    case 'invalid-email':
      return 'That email address doesn’t look right.';
    case 'quota-exceeded':
    case 'resource-exhausted':
      return 'Too many tries. Wait a minute and try again.';
    default:
      return (error as Error)?.message || 'Something went wrong. Please try again.';
  }
}
