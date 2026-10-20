/*
 * Device-level config editing. Edits go into a local draft immediately (optimistic UI)
 * and are written with an explicit Save. Saving merges the draft onto the latest
 * document (three-way, see lib/merge.ts) inside a transaction, so another helper's or
 * the parent's concurrent change to a different setting is never lost.
 */
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { friendlyError } from '../../data/api';
import { useData, useUser } from '../../data/DataProvider';
import { useConfigDoc } from '../../data/hooks';
import { changedPaths, deepEqual } from '../../lib/merge';
import type { DeviceConfig } from '../../lib/model';
import { describeRequest } from '../../lib/riskyChanges';
import type { ConfigDoc } from '../../lib/types';
import { useToast } from '../../components/Toast';
import { Button } from '../../components/Button';

interface Edits {
  base: DeviceConfig;
  draft: DeviceConfig;
}

export interface ConfigEditorValue {
  /** What the screens render: the draft while editing, otherwise the live document. */
  config: DeviceConfig | undefined;
  doc: ConfigDoc | undefined;
  loading: boolean;
  error: Error | undefined;
  dirty: boolean;
  changeCount: number;
  saving: boolean;
  /** Apply a change to the draft. The updater receives a copy it may mutate. */
  update: (mutate: (draft: DeviceConfig) => void) => void;
  save: () => Promise<boolean>;
  discard: () => void;
}

const Ctx = createContext<ConfigEditorValue | null>(null);

export function ConfigEditorProvider({ familyId, deviceId, parentName, children }: { familyId: string; deviceId: string; parentName: string; children: ReactNode }) {
  const { api } = useData();
  const user = useUser();
  const toast = useToast();
  const live = useConfigDoc(familyId, deviceId);
  const [edits, setEdits] = useState<Edits | null>(null);
  const [saving, setSaving] = useState(false);

  const remote = live.data?.config;
  const dirty = !!edits && !deepEqual(edits.base, edits.draft);
  // After a save, keep showing what we wrote until the snapshot catches up (no flicker).
  const showDraft = !!edits && (dirty || (remote?.revision ?? 0) < edits.base.revision);
  const config = showDraft ? edits!.draft : remote;

  const update = useCallback(
    (mutate: (draft: DeviceConfig) => void) => {
      setEdits((prev) => {
        const start = prev && (!deepEqual(prev.base, prev.draft) || (remote?.revision ?? 0) < prev.base.revision) ? prev : remote ? { base: remote, draft: remote } : null;
        if (!start) return prev;
        const draft = structuredClone(start.draft);
        mutate(draft);
        return { base: start.base, draft };
      });
    },
    [remote],
  );

  const save = useCallback(async () => {
    if (!edits || !dirty) return true;
    setSaving(true);
    try {
      const { config: written, held } = await api.saveConfig(familyId, deviceId, edits.base, edits.draft, { uid: user.uid, name: user.displayName });
      setEdits({ base: written, draft: written });
      if (held) {
        // docs/SECURITY.md §3: these wait for the parent's yes on the phone.
        toast(`Saved. ${parentName} will be asked on the phone about ${describeRequest(held)}, and everyone hears the answer.`);
      } else {
        toast(`Saved. ${parentName}’s phone will update in a few seconds.`);
      }
      return true;
    } catch (e) {
      toast(friendlyError(e), 'error');
      return false;
    } finally {
      setSaving(false);
    }
  }, [api, deviceId, dirty, edits, familyId, parentName, toast, user.displayName, user.uid]);

  const discard = useCallback(() => setEdits(null), []);

  // Don't let a closing tab silently drop unsaved edits.
  useEffect(() => {
    if (!dirty) return;
    const onBeforeUnload = (e: BeforeUnloadEvent) => {
      e.preventDefault();
    };
    window.addEventListener('beforeunload', onBeforeUnload);
    return () => window.removeEventListener('beforeunload', onBeforeUnload);
  }, [dirty]);

  const changeCount = useMemo(() => (dirty && edits ? changedPaths(edits.base, edits.draft).length : 0), [dirty, edits]);

  const value = useMemo<ConfigEditorValue>(
    () => ({ config, doc: live.data, loading: live.loading, error: live.error, dirty, changeCount, saving, update, save, discard }),
    [config, live.data, live.loading, live.error, dirty, changeCount, saving, update, save, discard],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useConfigEditor(): ConfigEditorValue {
  const v = useContext(Ctx);
  if (!v) throw new Error('useConfigEditor must be used inside <ConfigEditorProvider>');
  return v;
}

/** Sticky bar that appears while there are unsaved changes. */
export function SaveBar() {
  const { dirty, saving, save, discard, changeCount } = useConfigEditor();
  if (!dirty) return null;
  return (
    <div className="savebar" role="region" aria-label="Unsaved changes">
      <div className="savebar-inner">
        <p className="savebar-text">
          <strong>Unsaved changes</strong>
          <span className="muted"> · {changeCount === 1 ? '1 setting' : `${changeCount} settings`}</span>
        </p>
        <div className="row nowrap">
          <Button variant="ghost" onClick={discard} disabled={saving}>
            Discard
          </Button>
          <Button variant="primary" icon="check" loading={saving} onClick={() => void save()}>
            Save
          </Button>
        </div>
      </div>
    </div>
  );
}
