/*
 * In-memory DataApi for /demo: fixture data, no backend. Edits are kept for the
 * session so the demo is fully clickable (save a tile, upload a photo, invite a helper).
 */
import { threeWayMerge } from '../lib/merge';
import { newId } from '../lib/ids';
import { defaultDeviceConfig, type DeviceConfig } from '../lib/model';
import type { Device, EventDoc, Invite } from '../lib/types';
import type { AppUser, AuthApi, DataApi, Listener, Unsubscribe } from './api';
import { buildDemoState, DEMO_FAMILY_ID, DEMO_USER, type DemoState } from './demoFixtures';

type Watcher = () => void;

const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const randomCode = () => Array.from({ length: 6 }, () => CODE_ALPHABET[Math.floor(Math.random() * CODE_ALPHABET.length)]).join('');
const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

export function createDemoApi(options: { now?: number; latencyMs?: number } = {}): DataApi {
  let state: DemoState = buildDemoState(options.now ?? Date.now());
  let user: AppUser | null = { ...DEMO_USER };
  const latency = options.latencyMs ?? 250;
  const watchers = new Set<Watcher>();
  const userListeners = new Set<Listener<AppUser | null>>();
  const pendingClaims = new Map<string, ReturnType<typeof setTimeout>>();

  const commit = (next: DemoState) => {
    state = next;
    for (const w of [...watchers]) w();
  };

  function watch<T>(select: (s: DemoState) => T, cb: Listener<T>): Unsubscribe {
    let last: T | undefined;
    const run = () => {
      const v = select(state);
      if (v !== last) {
        last = v;
        cb(v);
      }
    };
    watchers.add(run);
    queueMicrotask(run);
    return () => watchers.delete(run);
  }

  const k = (f: string, d: string) => `${f}/${d}`;
  const devicesOf = (f: string) => state.devices[f] ?? [];

  const patchDevice = (familyId: string, deviceId: string, patch: Partial<Device>) =>
    commit({
      ...state,
      devices: { ...state.devices, [familyId]: devicesOf(familyId).map((d) => (d.id === deviceId ? { ...d, ...patch } : d)) },
    });

  const auth: AuthApi = {
    onUser(cb) {
      userListeners.add(cb);
      queueMicrotask(() => cb(user));
      return () => userListeners.delete(cb);
    },
    async signInWithGoogle() {
      await wait(latency);
      user = { ...DEMO_USER };
      userListeners.forEach((l) => l(user));
    },
    async sendEmailLink() {
      await wait(latency);
    },
    async completeEmailLink() {
      return 'not-a-link';
    },
    async devSignIn() {
      await this.signInWithGoogle();
    },
    async signOut() {
      user = null;
      userListeners.forEach((l) => l(user));
    },
  };

  const api: DataApi = {
    mode: 'demo',
    auth,

    watchMyFamilies: (uid, cb) => watch((s) => s.families, (fams) => cb(Object.values(fams).filter((f) => f.memberUids.includes(uid)))),
    watchFamily: (f, cb) => watch((s) => s.families[f] ?? null, cb),
    watchMyMembership: (f, uid, cb) =>
      watch(
        (s) => s.members[f]?.find((m) => m.uid === uid)?.status ?? 'declined',
        (st) => cb(st === 'active' ? 'active' : st === 'pending' ? 'pending' : 'declined'),
      ),
    watchMembers: (f, cb) => watch((s) => s.members[f] ?? [], cb),
    watchDevices: (f, cb) => watch((s) => s.devices[f] ?? [], cb),
    watchDevice: (f, d, cb) => watch((s) => (s.devices[f] ?? []).find((x) => x.id === d) ?? null, cb),
    watchConfig: (f, d, cb) =>
      watch(
        (s) => s.configs[k(f, d)] ?? { config: defaultDeviceConfig(), updatedBy: null, updatedAt: null, exists: false },
        cb,
      ),
    watchEvents: (f, d, max, cb) => watch((s) => s.events[k(f, d)] ?? [], (list) => cb([...list].sort((a, b) => b.at - a.at).slice(0, max))),
    watchPhotos: (f, d, cb) => watch((s) => s.photos[k(f, d)] ?? [], cb),
    watchChanges: (f, d, max, cb) => watch((s) => s.changes[k(f, d)] ?? [], (list) => cb(list.slice(0, max))),

    async fileUrl(path) {
      return path;
    },

    async saveConfig(f, d, base, draft, by) {
      await wait(latency);
      const current = state.configs[k(f, d)]?.config ?? defaultDeviceConfig();
      const merged: DeviceConfig = { ...threeWayMerge(base, draft, current), revision: Date.now() };
      const change = { id: newId('c'), byUid: by.uid, byName: by.name, summary: `${by.name} updated the phone settings`, at: Date.now() };
      commit({
        ...state,
        configs: { ...state.configs, [k(f, d)]: { config: merged, updatedBy: { ...by, kind: 'helper' }, updatedAt: Date.now(), exists: true } },
        changes: { ...state.changes, [k(f, d)]: [change, ...(state.changes[k(f, d)] ?? [])] },
      });
      return merged;
    },

    async uploadPhoto(f, d, jpeg, caption, by) {
      await wait(latency * 2);
      const url = URL.createObjectURL(jpeg);
      const photo = { id: newId('p'), storagePath: url, fromUid: by.uid, fromName: by.name, caption, createdAt: Date.now() };
      commit({ ...state, photos: { ...state.photos, [k(f, d)]: [photo, ...(state.photos[k(f, d)] ?? [])] } });
    },
    async deletePhoto(f, d, photo) {
      await wait(latency);
      commit({ ...state, photos: { ...state.photos, [k(f, d)]: (state.photos[k(f, d)] ?? []).filter((p) => p.id !== photo.id) } });
    },
    async uploadContactPhoto(_f, _d, jpeg) {
      await wait(latency);
      return URL.createObjectURL(jpeg);
    },

    async updateMyMember(f, uid, patch) {
      await wait(latency);
      commit({ ...state, members: { ...state.members, [f]: (state.members[f] ?? []).map((m) => (m.uid === uid ? { ...m, ...patch } : m)) } });
    },
    async addPushToken(f, uid, token) {
      commit({
        ...state,
        members: { ...state.members, [f]: (state.members[f] ?? []).map((m) => (m.uid === uid ? { ...m, pushTokens: [...new Set([...m.pushTokens, token])] } : m)) },
      });
    },
    async removePushToken(f, uid, token) {
      commit({
        ...state,
        members: { ...state.members, [f]: (state.members[f] ?? []).map((m) => (m.uid === uid ? { ...m, pushTokens: m.pushTokens.filter((t) => t !== token) } : m)) },
      });
    },
    async renameDevice(f, d, parentName) {
      await wait(latency);
      patchDevice(f, d, { parentName });
    },

    async createFamily({ familyName, parentName, helperPhone }) {
      await wait(latency * 2);
      const familyId = newId('fam');
      const deviceId = newId('dev');
      const u = user ?? DEMO_USER;
      const now = Date.now();
      const config = defaultDeviceConfig({ parentName });
      config.family = [{ id: `fam_${u.uid}`, name: u.displayName, relation: '', phone: helperPhone ?? '', isPrimaryHelper: true }];
      commit({
        ...state,
        families: { ...state.families, [familyId]: { id: familyId, name: familyName, plan: 'free', ownerUid: u.uid, memberUids: [u.uid], createdAt: now } },
        members: {
          ...state.members,
          [familyId]: [{ uid: u.uid, role: 'owner', displayName: u.displayName, email: u.email, photoUrl: null, phone: helperPhone ?? '', pushTokens: [], notify: { urgent: 'push', notice: 'push' }, status: 'active', joinedAt: now }],
        },
        devices: { ...state.devices, [familyId]: [emptyDevice(deviceId, parentName, now)] },
        configs: { ...state.configs, [k(familyId, deviceId)]: { config, updatedBy: null, updatedAt: now, exists: true } },
      });
      return { familyId, deviceId };
    },
    async addDevice({ familyId, parentName }) {
      await wait(latency);
      const deviceId = newId('dev');
      const now = Date.now();
      commit({
        ...state,
        devices: { ...state.devices, [familyId]: [...devicesOf(familyId), emptyDevice(deviceId, parentName, now)] },
        configs: { ...state.configs, [k(familyId, deviceId)]: { config: defaultDeviceConfig({ parentName }), updatedBy: null, updatedAt: now, exists: true } },
      });
      return { deviceId };
    },
    async createDeviceInvite({ familyId, deviceId }): Promise<Invite> {
      await wait(latency);
      const invite = { code: randomCode(), expiresAt: Date.now() + 24 * 3600_000 };
      // Pretend the parent's phone enters the code a few seconds later.
      const key = k(familyId, deviceId);
      clearTimeout(pendingClaims.get(key));
      pendingClaims.set(
        key,
        setTimeout(() => {
          const now = Date.now();
          patchDevice(familyId, deviceId, {
            deviceUid: newId('anon'),
            model: 'Samsung Galaxy A15',
            androidVersion: '14',
            appVersion: '1.0.0',
            status: { protected: true, missing: [], lastHeartbeatAt: now, lastSeenAt: now },
            installedApps: (state.devices[DEMO_FAMILY_ID]?.[0]?.installedApps ?? []).slice(0, 10),
          });
        }, 8000),
      );
      return invite;
    },
    async createHelperInvite() {
      await wait(latency);
      return { code: randomCode(), expiresAt: Date.now() + 24 * 3600_000 };
    },
    async acceptHelperInvite({ displayName, phone }) {
      await wait(latency);
      // Join as a pending helper; the "parent" approves on the phone a few seconds later.
      const u = user ?? DEMO_USER;
      const familyId = DEMO_FAMILY_ID;
      const others = (state.members[familyId] ?? []).filter((m) => m.uid !== u.uid);
      const me = (state.members[familyId] ?? []).find((m) => m.uid === u.uid);
      if (me && me.status === 'active') return { familyId, status: 'active' as const };
      commit({
        ...state,
        members: {
          ...state.members,
          [familyId]: [...others, { uid: u.uid, role: 'helper', displayName, email: u.email, photoUrl: null, phone: phone ?? '', pushTokens: [], notify: { urgent: 'push', notice: 'push' }, status: 'pending', joinedAt: Date.now() }],
        },
      });
      setTimeout(() => {
        commit({ ...state, members: { ...state.members, [familyId]: (state.members[familyId] ?? []).map((m) => (m.uid === u.uid ? { ...m, status: 'active' } : m)) } });
      }, 6000);
      return { familyId, status: 'pending' as const };
    },
    async removeHelper({ familyId, uid }) {
      await wait(latency);
      const fam = state.families[familyId];
      commit({
        ...state,
        families: fam ? { ...state.families, [familyId]: { ...fam, memberUids: fam.memberUids.filter((u) => u !== uid) } } : state.families,
        members: { ...state.members, [familyId]: (state.members[familyId] ?? []).filter((m) => m.uid !== uid) },
      });
    },
    async deleteDevice({ familyId, deviceId }) {
      await wait(latency);
      commit({ ...state, devices: { ...state.devices, [familyId]: devicesOf(familyId).filter((d) => d.id !== deviceId) } });
    },
    async deleteFamily({ familyId }) {
      await wait(latency);
      const families = { ...state.families };
      delete families[familyId];
      commit({ ...state, families });
    },
  };

  // Exposed for screenshots and manual demos: window.__demo.addEvent(...)
  if (typeof window !== 'undefined') {
    (window as unknown as { __demo?: unknown }).__demo = {
      addEvent(familyId: string, deviceId: string, event: Omit<EventDoc, 'id' | 'createdAt' | 'deliveredTo' | 'source'>) {
        const e: EventDoc = { ...event, id: newId('e'), createdAt: Date.now(), deliveredTo: [], source: 'device' };
        commit({ ...state, events: { ...state.events, [k(familyId, deviceId)]: [e, ...(state.events[k(familyId, deviceId)] ?? [])] } });
      },
    };
  }

  return api;
}

function emptyDevice(id: string, parentName: string, now: number): Device {
  return {
    id,
    parentName,
    deviceUid: null,
    model: '',
    androidVersion: '',
    appVersion: '',
    status: null,
    installedApps: [],
    checkIn: null,
    consent: null,
    createdAt: now,
  };
}
