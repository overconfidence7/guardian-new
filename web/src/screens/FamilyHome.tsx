import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { friendlyError } from '../data/api';
import { useData, usePaths } from '../data/DataProvider';
import { useDevices, useEvents, useFamily, useMembers, useNow } from '../data/hooks';
import { Avatar } from '../components/Avatar';
import { Button, ButtonA, ButtonLink } from '../components/Button';
import { Dialog } from '../components/Dialog';
import { EmptyState, Notice, Spinner } from '../components/EmptyState';
import { EventItem } from '../components/EventItem';
import { Field, Input } from '../components/Field';
import { Icon } from '../components/Icon';
import { useToast } from '../components/Toast';
import { deviceHealth } from '../lib/status';
import { relativeTime } from '../lib/time';
import { telHref, useParentPhone } from '../lib/parentPhone';
import type { Device } from '../lib/types';

export function DeviceCard({ familyId, device, now }: { familyId: string; device: Device; now: number }) {
  const paths = usePaths();
  const events = useEvents(familyId, device.id, 3);
  const health = deviceHealth(device, now);
  const parentPhone = useParentPhone(device.id);
  const last = device.status?.lastHeartbeatAt ?? device.status?.lastSeenAt ?? null;
  const recent = (events.data ?? []).filter((e) => e.severity !== 'INFO');
  const urgentCount = recent.filter((e) => e.severity === 'URGENT' && now - e.at < 24 * 3600_000).length;

  return (
    <article className={`device-card dc-${health.kind}`} aria-labelledby={`dc-${device.id}`}>
      <div className="device-card-head">
        <Avatar name={device.parentName} size={56} />
        <div className="grow">
          <h2 id={`dc-${device.id}`} className="device-card-title">
            <Link to={health.kind === 'not-set-up' ? paths.setup(familyId, device.id) : paths.device(familyId, device.id)} className="stretched">
              {device.parentName}’s phone
            </Link>
          </h2>
          <p className="device-card-sub">
            {health.kind === 'not-set-up' ? 'Waiting to be connected' : last ? `Last active ${relativeTime(last, now)}` : 'Connected'}
            {device.model ? <span className="subtle"> · {device.model}</span> : null}
          </p>
        </div>
        <Icon name="chevronRight" size={22} className="device-card-chevron" />
      </div>

      <div className={`health health-${health.kind}`}>
        <span className={`status-dot ${health.kind === 'protected' ? 'ok' : health.kind === 'attention' ? 'attention' : health.kind === 'quiet' ? 'quiet' : ''}`} aria-hidden="true" />
        <div className="grow">
          <strong>{health.label}</strong>
          {health.kind === 'attention' ? (
            <ul className="health-list">
              {health.items.map((i) => (
                <li key={i.short}>{i.short}</li>
              ))}
            </ul>
          ) : (
            <span className="health-detail"> · {health.detail}</span>
          )}
        </div>
      </div>

      {health.kind === 'not-set-up' ? (
        <div className="device-card-foot">
          <ButtonLink to={paths.setup(familyId, device.id)} variant="primary" icon="qr" className="raise">
            Get the setup code
          </ButtonLink>
        </div>
      ) : (
        <>
          <div className="device-card-events">
            <div className="row spread">
              <h3 className="mini-title">
                Latest {urgentCount > 0 ? <span className="badge badge-urgent">{urgentCount} need you</span> : null}
              </h3>
            </div>
            {events.loading ? (
              <p className="subtle small">Loading…</p>
            ) : recent.length === 0 ? (
              <p className="quiet-line">
                <Icon name="sun" size={18} /> All quiet. Nothing needs you.
              </p>
            ) : (
              <ul className="list-plain stack" style={{ ['--gap' as string]: 'var(--s-2)' }}>
                {recent.map((e) => (
                  <li key={e.id}>
                    <EventItem event={e} parentName={device.parentName} now={now} compact />
                  </li>
                ))}
              </ul>
            )}
          </div>
          <div className="device-card-foot">
            <ButtonLink to={paths.device(familyId, device.id, 'alerts')} variant="quiet" className="raise">
              See all
            </ButtonLink>
            {parentPhone ? (
              <ButtonA href={telHref(parentPhone)} variant="secondary" icon="phone" className="raise">
                Call {device.parentName}
              </ButtonA>
            ) : null}
          </div>
        </>
      )}
    </article>
  );
}

export function FamilyHome() {
  const { familyId = '' } = useParams();
  const { api } = useData();
  const paths = usePaths();
  const navigate = useNavigate();
  const toast = useToast();
  const family = useFamily(familyId);
  const devices = useDevices(familyId);
  const members = useMembers(familyId);
  const now = useNow(30_000);
  const [adding, setAdding] = useState(false);
  const [newName, setNewName] = useState('');
  const [busy, setBusy] = useState(false);

  if (family.loading || devices.loading) return <Spinner />;
  if (!family.data) {
    return (
      <div className="page">
        <EmptyState icon="users" title="We couldn’t open this family" action={<ButtonLink to={paths.home()}>Go home</ButtonLink>}>
          You may not be a member, or it was deleted.
        </EmptyState>
      </div>
    );
  }

  const list = devices.data ?? [];
  const maxDevices = family.data.plan === 'family' ? 2 : 1;
  const active = (members.data ?? []).filter((m) => m.status === 'active');
  const pending = (members.data ?? []).filter((m) => m.status === 'pending');

  async function addPhone() {
    if (!newName.trim()) return;
    setBusy(true);
    try {
      const { deviceId } = await api.addDevice({ familyId, parentName: newName.trim() });
      setAdding(false);
      navigate(paths.setup(familyId, deviceId));
    } catch (e) {
      toast(friendlyError(e), 'error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="page">
      <header className="page-header row spread">
        <div>
          <p className="eyebrow">{family.data.name}</p>
          <h1 className="h-page">{greeting(now)}</h1>
        </div>
        <div className="avatar-stack" aria-label={`Helpers: ${active.map((m) => m.displayName).join(', ')}`}>
          {active.slice(0, 4).map((m) => (
            <Avatar key={m.uid} name={m.displayName} src={m.photoUrl} size={38} />
          ))}
        </div>
      </header>

      {pending.length > 0 ? (
        <Notice tone="info" icon="users" title={`${pending.map((m) => m.displayName).join(' and ')} ${pending.length === 1 ? 'is' : 'are'} waiting to join`}>
          They’ll be able to help once {list[0]?.parentName ?? 'your parent'} approves them on the phone.
        </Notice>
      ) : null}

      {list.length === 0 ? (
        <EmptyState icon="smartphone" title="No phones yet" action={<Button variant="primary" icon="plus" onClick={() => setAdding(true)}>Add a phone</Button>}>
          Add your parent’s phone to get a setup code.
        </EmptyState>
      ) : (
        <div className="device-grid">
          {list.map((d) => (
            <DeviceCard key={d.id} familyId={familyId} device={d} now={now} />
          ))}
          {list.length < maxDevices ? (
            <button type="button" className="add-device" onClick={() => setAdding(true)}>
              <Icon name="plus" size={26} />
              <span>
                <strong>Add another phone</strong>
                <span className="small muted">Your plan includes {maxDevices} parent phones.</span>
              </span>
            </button>
          ) : null}
        </div>
      )}

      <Dialog
        open={adding}
        onClose={() => setAdding(false)}
        title="Add a parent’s phone"
        description="You’ll get a setup code to enter on their phone."
        size="sm"
        footer={
          <>
            <Button variant="ghost" onClick={() => setAdding(false)}>
              Cancel
            </Button>
            <Button variant="primary" loading={busy} onClick={() => void addPhone()} disabled={!newName.trim()}>
              Continue
            </Button>
          </>
        }
      >
        <Field label="What do you call them?">
          {(ids) => (
            <Input
              {...ids}
              autoFocus
              placeholder="Dad"
              value={newName}
              onChange={(e) => setNewName(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') void addPhone();
              }}
            />
          )}
        </Field>
      </Dialog>
    </div>
  );
}

function greeting(now: number): string {
  const h = new Date(now).getHours();
  if (h < 5) return 'Good evening';
  if (h < 12) return 'Good morning';
  if (h < 18) return 'Good afternoon';
  return 'Good evening';
}
