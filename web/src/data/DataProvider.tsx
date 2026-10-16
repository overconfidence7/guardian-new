import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import type { AppUser, DataApi } from './api';

export interface DataContextValue {
  api: DataApi;
  user: AppUser | null;
  /** True once the auth state is known. */
  authReady: boolean;
  /** Route prefix: "" for the real app, "/demo" for the fixture-backed demo. */
  base: string;
}

const DataContext = createContext<DataContextValue | null>(null);

let demoApi: DataApi | null = null;

async function loadApi(mode: 'firebase' | 'demo'): Promise<DataApi> {
  if (mode === 'demo') {
    if (!demoApi) demoApi = (await import('./demoApi')).createDemoApi();
    return demoApi;
  }
  return (await import('./firebaseApi')).firebaseApi;
}

export function DataProvider({ mode, base, api: injected, children }: { mode: 'firebase' | 'demo'; base: string; api?: DataApi; children: ReactNode }) {
  const [loaded, setLoaded] = useState<DataApi | null>(null);
  const api = injected ?? loaded;
  const [auth, setAuth] = useState<{ api: DataApi | null; user: AppUser | null; ready: boolean }>({ api: null, user: null, ready: false });

  useEffect(() => {
    if (injected) return;
    let alive = true;
    loadApi(mode).then(
      (a) => alive && setLoaded(a),
      (e) => console.error('Could not start the data layer', e),
    );
    return () => {
      alive = false;
    };
  }, [mode, injected]);

  useEffect(() => {
    if (!api) return;
    return api.auth.onUser((user) => setAuth({ api, user, ready: true }));
  }, [api]);

  const value = useMemo<DataContextValue | null>(() => {
    if (!api) return null;
    const current = auth.api === api;
    return { api, user: current ? auth.user : null, authReady: current && auth.ready, base };
  }, [api, auth, base]);

  if (!value) return <div className="boot" aria-busy="true" aria-label="Loading" />;
  return <DataContext.Provider value={value}>{children}</DataContext.Provider>;
}

export function useData(): DataContextValue {
  const ctx = useContext(DataContext);
  if (!ctx) throw new Error('useData must be used inside <DataProvider>');
  return ctx;
}

/** The signed-in user; only call under a route that requires sign-in. */
export function useUser(): AppUser {
  const { user } = useData();
  if (!user) throw new Error('No signed-in user');
  return user;
}

/** Build app paths that respect the /demo prefix. */
export function usePaths() {
  const { base } = useData();
  return useMemo(
    () => ({
      base,
      home: () => base || '/',
      welcome: () => `${base}/welcome`,
      family: (f: string) => `${base}/f/${f}`,
      familySettings: (f: string) => `${base}/f/${f}/settings`,
      device: (f: string, d: string, tab = 'alerts') => `${base}/f/${f}/d/${d}/${tab}`,
      setup: (f: string, d: string) => `${base}/f/${f}/d/${d}/setup`,
      join: (code: string) => `${base}/join/${code}`,
    }),
    [base],
  );
}
