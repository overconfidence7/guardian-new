import { useEffect, useState } from 'react';
import type { ChangeDoc, ConfigDoc, Device, EventDoc, Family, Member, PhotoDoc } from '../lib/types';
import type { ErrorListener, Listener, Unsubscribe } from './api';
import { useData } from './DataProvider';

export interface Live<T> {
  data: T | undefined;
  error: Error | undefined;
  loading: boolean;
}

/**
 * Subscribe to a live query identified by `key` (null = don't subscribe). State is
 * tagged with the key so switching queries never shows the previous query's data.
 */
export function useLive<T>(key: string | null, subscribe: (cb: Listener<T>, onError: ErrorListener) => Unsubscribe): Live<T> {
  const [state, setState] = useState<{ key: string | null; data?: T; error?: Error }>({ key: null });
  useEffect(() => {
    if (key === null) return;
    return subscribe(
      (data) => setState({ key, data }),
      (error) => setState({ key, error }),
    );
    // The key fully identifies the subscription.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);
  const mine = state.key === key;
  const data = mine ? state.data : undefined;
  const error = mine ? state.error : undefined;
  return { data, error, loading: key !== null && data === undefined && error === undefined };
}

export function useMyFamilies(): Live<Family[]> {
  const { api, user } = useData();
  return useLive(user ? `fams:${user.uid}` : null, (cb, e) => api.watchMyFamilies(user!.uid, cb, e));
}

export function useFamily(familyId: string | undefined): Live<Family | null> {
  const { api } = useData();
  return useLive(familyId ? `fam:${familyId}` : null, (cb, e) => api.watchFamily(familyId!, cb, e));
}

export function useMembers(familyId: string | undefined): Live<Member[]> {
  const { api } = useData();
  return useLive(familyId ? `members:${familyId}` : null, (cb, e) => api.watchMembers(familyId!, cb, e));
}

export function useDevices(familyId: string | undefined): Live<Device[]> {
  const { api } = useData();
  return useLive(familyId ? `devices:${familyId}` : null, (cb, e) => api.watchDevices(familyId!, cb, e));
}

export function useDevice(familyId: string | undefined, deviceId: string | undefined): Live<Device | null> {
  const { api } = useData();
  return useLive(familyId && deviceId ? `device:${familyId}/${deviceId}` : null, (cb, e) => api.watchDevice(familyId!, deviceId!, cb, e));
}

export function useConfigDoc(familyId: string | undefined, deviceId: string | undefined): Live<ConfigDoc> {
  const { api } = useData();
  return useLive(familyId && deviceId ? `config:${familyId}/${deviceId}` : null, (cb, e) => api.watchConfig(familyId!, deviceId!, cb, e));
}

export function useEvents(familyId: string | undefined, deviceId: string | undefined, max = 50): Live<EventDoc[]> {
  const { api } = useData();
  return useLive(familyId && deviceId ? `events:${familyId}/${deviceId}:${max}` : null, (cb, e) => api.watchEvents(familyId!, deviceId!, max, cb, e));
}

export function usePhotos(familyId: string | undefined, deviceId: string | undefined): Live<PhotoDoc[]> {
  const { api } = useData();
  return useLive(familyId && deviceId ? `photos:${familyId}/${deviceId}` : null, (cb, e) => api.watchPhotos(familyId!, deviceId!, cb, e));
}

export function useChanges(familyId: string | undefined, deviceId: string | undefined, max = 30): Live<ChangeDoc[]> {
  const { api } = useData();
  return useLive(familyId && deviceId ? `changes:${familyId}/${deviceId}:${max}` : null, (cb, e) => api.watchChanges(familyId!, deviceId!, max, cb, e));
}

/** Resolve a Storage path to a URL (cached per session). */
const urlCache = new Map<string, Promise<string>>();
export function useFileUrl(storagePath: string | undefined | null): string | undefined {
  const { api } = useData();
  const [resolved, setResolved] = useState<{ path: string; url: string } | null>(null);
  useEffect(() => {
    if (!storagePath) return;
    if (/^(data:|blob:|https?:)/.test(storagePath)) return;
    let alive = true;
    let p = urlCache.get(storagePath);
    if (!p) {
      p = api.fileUrl(storagePath);
      urlCache.set(storagePath, p);
      p.catch(() => urlCache.delete(storagePath));
    }
    p.then(
      (url) => alive && setResolved({ path: storagePath, url }),
      () => undefined,
    );
    return () => {
      alive = false;
    };
  }, [api, storagePath]);
  if (!storagePath) return undefined;
  if (/^(data:|blob:|https?:)/.test(storagePath)) return storagePath;
  return resolved?.path === storagePath ? resolved.url : undefined;
}

/** Re-render every `ms` so relative times stay fresh. */
export function useNow(ms = 30_000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(id);
  }, [ms]);
  return now;
}
