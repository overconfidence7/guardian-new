import { useState } from 'react';
import { useNavigate } from 'react-router';
import { friendlyError } from '../../data/api';
import { useData, usePaths } from '../../data/DataProvider';
import { useFamily, useNow } from '../../data/hooks';
import { Button } from '../../components/Button';
import { Card, CardHeader } from '../../components/Card';
import { Dialog } from '../../components/Dialog';
import { Spinner } from '../../components/EmptyState';
import { Checkbox, Field, Input, RadioCards, Slider } from '../../components/Field';
import { Icon } from '../../components/Icon';
import { Switch } from '../../components/Switch';
import { useToast } from '../../components/Toast';
import { builtInCategory } from '../../lib/appCatalog';
import type { ThemePreference } from '../../lib/model';
import { relativeTime } from '../../lib/time';
import type { Device } from '../../lib/types';
import { useConfigEditor } from './ConfigEditor';

export function FontPreview({ scale, highContrast, dark }: { scale: number; highContrast: boolean; dark: boolean }) {
  return (
    <div className={['font-preview', highContrast ? 'is-contrast' : '', dark ? 'is-dark' : ''].join(' ')} style={{ ['--fs' as string]: String(scale) }} aria-label="Text size preview">
      <p className="fp-time">9:41</p>
      <p className="fp-title">Blood pressure pill</p>
      <p className="fp-body">One white tablet with water</p>
      <div className="fp-buttons">
        <span className="fp-btn fp-primary">Done</span>
        <span className="fp-btn">In 10 minutes</span>
      </div>
    </div>
  );
}

export function PhoneTab({ familyId, device }: { familyId: string; device: Device }) {
  const { config, update, loading } = useConfigEditor();
  const { api, user } = useData();
  const family = useFamily(familyId);
  const paths = usePaths();
  const navigate = useNavigate();
  const toast = useToast();
  const now = useNow(60_000);
  const [name, setName] = useState(device.parentName);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [removing, setRemoving] = useState(false);
  const [query, setQuery] = useState('');

  if (loading || !config) return <Spinner />;
  const d = config.display;
  const isOwner = family.data?.ownerUid === user?.uid;
  const apps = device.installedApps
    .filter((a) => builtInCategory(a.packageName) !== 'REMOTE_ACCESS')
    .filter((a) => a.label.toLowerCase().includes(query.trim().toLowerCase()))
    .sort((a, b) => a.label.localeCompare(b.label));
  const allowed = new Set(config.allowedApps);

  async function rename() {
    if (!name.trim() || name.trim() === device.parentName) return;
    try {
      await api.renameDevice(familyId, device.id, name.trim());
      toast('Name updated.');
    } catch (e) {
      toast(friendlyError(e), 'error');
    }
  }

  async function remove() {
    setRemoving(true);
    try {
      await api.deleteDevice({ familyId, deviceId: device.id });
      toast(`${device.parentName}’s phone was removed from the family.`);
      navigate(paths.family(familyId), { replace: true });
    } catch (e) {
      toast(friendlyError(e), 'error');
      setRemoving(false);
    }
  }

  return (
    <div className="stack" style={{ ['--gap' as string]: 'var(--s-5)' }}>
      <Card>
        <CardHeader title="Text and display" description={`Bigger text is easier on the eyes. Changes appear on ${device.parentName}’s phone after you save.`} />
        <div className="display-grid">
          <div className="stack" style={{ ['--gap' as string]: 'var(--s-5)' }}>
            <Slider
              label="Text size"
              value={Math.round(d.fontScale * 100)}
              min={100}
              max={200}
              step={10}
              format={(v) => `${v}%`}
              hint="130% is the comfortable default. Go bigger if they squint or hold the phone far away."
              onChange={(v) => update((c) => void (c.display.fontScale = Math.round(v) / 100))}
            />
            <Switch
              label="High contrast"
              description="Pure black and white with bold outlines. Helps with cataracts or low vision."
              checked={d.highContrast}
              onChange={(v) => update((c) => void (c.display.highContrast = v))}
            />
            <RadioCards<ThemePreference>
              legend="Colors"
              name="theme"
              value={d.theme}
              onChange={(v) => update((c) => void (c.display.theme = v))}
              options={[
                { value: 'LIGHT', title: 'Light', description: 'Dark text on a light background. Easiest for most people.' },
                { value: 'DARK', title: 'Dark', description: 'Light text on black. Gentler at night.' },
                { value: 'SYSTEM', title: 'Follow the phone', description: 'Switches with the phone’s own setting.' },
              ]}
            />
          </div>
          <FontPreview scale={d.fontScale} highContrast={d.highContrast} dark={d.theme === 'DARK'} />
        </div>
      </Card>

      <Card>
        <CardHeader title="Apps in “All my apps”" description={`Only the apps you tick show up in ${device.parentName}’s app list. Others stay installed but out of the way.`} />
        {device.installedApps.length === 0 ? (
          <p className="muted">Once the phone is connected, its apps appear here.</p>
        ) : (
          <>
            <Input type="search" placeholder="Search apps" aria-label="Search apps" value={query} onChange={(e) => setQuery(e.target.value)} />
            <p className="small subtle" style={{ marginTop: 'var(--s-2)' }}>
              {allowed.size} of {device.installedApps.length} apps allowed
            </p>
            <div className="app-list">
              {apps.map((a) => (
                <Checkbox
                  key={a.packageName}
                  label={a.label}
                  checked={allowed.has(a.packageName)}
                  onChange={(v) =>
                    update((c) => {
                      const set = new Set(c.allowedApps);
                      if (v) set.add(a.packageName);
                      else set.delete(a.packageName);
                      c.allowedApps = device.installedApps.map((x) => x.packageName).filter((p) => set.has(p)).concat([...set].filter((p) => !device.installedApps.some((x) => x.packageName === p)));
                    })
                  }
                />
              ))}
            </div>
            <p className="small subtle">Remote-control apps are never listed here.</p>
          </>
        )}
      </Card>

      <Card>
        <CardHeader title="About this phone" />
        <dl className="facts">
          <div>
            <dt>Phone</dt>
            <dd>{device.model || '—'}</dd>
          </div>
          <div>
            <dt>Android</dt>
            <dd>{device.androidVersion ? `Android ${device.androidVersion}` : '—'}</dd>
          </div>
          <div>
            <dt>Guardian Mode</dt>
            <dd>{device.appVersion ? `Version ${device.appVersion}` : '—'}</dd>
          </div>
          <div>
            <dt>Last check-in</dt>
            <dd>{relativeTime(device.status?.lastHeartbeatAt ?? null, now)}</dd>
          </div>
          <div>
            <dt>Connected</dt>
            <dd>{device.consent?.acceptedAt ? new Date(device.consent.acceptedAt).toLocaleDateString('en-US', { month: 'long', day: 'numeric', year: 'numeric' }) : device.deviceUid ? 'Yes' : 'Not yet'}</dd>
          </div>
        </dl>
        <form
          className="rename"
          onSubmit={(e) => {
            e.preventDefault();
            void rename();
          }}
        >
          <Field label="What you call them" hint="Used in your alerts, like “Mom opened Chase…”.">
            {(ids) => <Input {...ids} maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />}
          </Field>
          <Button type="submit" variant="secondary" disabled={!name.trim() || name.trim() === device.parentName}>
            Save name
          </Button>
        </form>
      </Card>

      {isOwner ? (
        <Card className="danger-zone">
          <CardHeader title="Remove this phone" description={`Disconnects ${device.parentName}’s phone from the family and deletes its alerts, photos and settings here. Guardian Mode on the phone goes back to a fresh start.`} />
          <Button variant="danger" icon="trash" onClick={() => setConfirmRemove(true)}>
            Remove {device.parentName}’s phone
          </Button>
        </Card>
      ) : null}

      <Dialog
        open={confirmRemove}
        onClose={() => setConfirmRemove(false)}
        size="sm"
        title={`Remove ${device.parentName}’s phone?`}
        description="This can’t be undone. You can always connect it again with a new setup code."
        footer={
          <>
            <Button variant="ghost" onClick={() => setConfirmRemove(false)}>
              Keep it
            </Button>
            <Button variant="danger" className="btn-solid" loading={removing} onClick={() => void remove()}>
              Remove phone
            </Button>
          </>
        }
      >
        <p className="small muted">
          <Icon name="info" size={16} className="inline-icon" /> {device.parentName} will see that the family connection ended.
        </p>
      </Dialog>
    </div>
  );
}
