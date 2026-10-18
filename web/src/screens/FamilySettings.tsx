import { useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { friendlyError } from '../data/api';
import { useData, usePaths, useUser } from '../data/DataProvider';
import { useDevices, useFamily, useMembers } from '../data/hooks';
import { Avatar } from '../components/Avatar';
import { Button, IconButton } from '../components/Button';
import { Card, CardHeader } from '../components/Card';
import { Dialog } from '../components/Dialog';
import { Badge, Notice, Spinner } from '../components/EmptyState';
import { Field, Input } from '../components/Field';
import { Icon } from '../components/Icon';
import { Switch } from '../components/Switch';
import { useToast } from '../components/Toast';
import { forgetPushToken, getPushToken, pushSupport, savedPushToken } from '../lib/pwa';
import type { Invite, Member } from '../lib/types';

function InviteDialog({ familyId, onClose }: { familyId: string; onClose: () => void }) {
  const { api } = useData();
  const toast = useToast();
  const [invite, setInvite] = useState<Invite | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const link = invite ? `${window.location.origin}/join/${invite.code}` : '';

  async function create() {
    setBusy(true);
    setError(null);
    try {
      setInvite(await api.createHelperInvite({ familyId }));
    } catch (e) {
      setError(friendlyError(e));
    } finally {
      setBusy(false);
    }
  }

  async function copy() {
    try {
      await navigator.clipboard.writeText(link);
      toast('Link copied.');
    } catch {
      toast('Couldn’t copy. Press and hold the link to copy it.', 'info');
    }
  }

  return (
    <Dialog
      open
      onClose={onClose}
      title="Invite a helper"
      description="A brother, sister or close friend who can help with the phone. They’ll need to be approved on the parent’s phone before they can see anything."
    >
      {!invite ? (
        <>
          {error ? <Notice tone="notice" title="We couldn’t make an invite">{error}</Notice> : null}
          <Button variant="primary" size="lg" block icon="link" loading={busy} onClick={() => void create()}>
            Create invite link
          </Button>
        </>
      ) : (
        <>
          <div className="invite-link">
            <code data-testid="invite-link">{link}</code>
          </div>
          <div className="row">
            {typeof navigator.share === 'function' ? (
              <Button variant="primary" icon="share" onClick={() => void navigator.share({ title: 'Join our family on Guardian Mode', text: 'Help with the phone on Guardian Mode:', url: link }).catch(() => undefined)}>
                Share
              </Button>
            ) : null}
            <Button variant="secondary" icon="copy" onClick={() => void copy()}>
              Copy link
            </Button>
          </div>
          <p className="small muted">
            The link works once and expires {new Date(invite.expiresAt).toLocaleString('en-US', { weekday: 'long', hour: 'numeric', minute: '2-digit' })}.
          </p>
        </>
      )}
    </Dialog>
  );
}

function NotificationsCard({ familyId, me }: { familyId: string; me: Member }) {
  const { api } = useData();
  const toast = useToast();
  const support = pushSupport();
  const [busy, setBusy] = useState(false);
  const token = savedPushToken();
  const enabledHere = api.mode === 'demo' ? me.pushTokens.length > 0 : !!token && me.pushTokens.includes(token);

  async function enable() {
    setBusy(true);
    try {
      const t = api.mode === 'demo' ? 'demo-token' : await getPushToken();
      await api.addPushToken(familyId, me.uid, t);
      toast('Notifications are on for this device.');
    } catch (e) {
      toast(friendlyError(e), 'error');
    } finally {
      setBusy(false);
    }
  }

  async function disable() {
    setBusy(true);
    try {
      const t = api.mode === 'demo' ? 'demo-token' : token;
      if (t) await api.removePushToken(familyId, me.uid, t);
      forgetPushToken();
      toast('Notifications are off for this device.');
    } catch (e) {
      toast(friendlyError(e), 'error');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Card>
      <CardHeader title="Notifications" description="Things that need you now always come through. You choose about the rest." />
      <div className="stack" style={{ ['--gap' as string]: 'var(--s-5)' }}>
        {support.kind === 'ios-needs-home-screen' && api.mode !== 'demo' ? (
          <Notice tone="info" icon="smartphone" title="On iPhone, add Guardian Mode to your Home Screen first">
            <ol className="ios-steps">
              <li>
                Tap the <strong>Share</strong> button <Icon name="share" size={16} className="inline-icon" /> in Safari.
              </li>
              <li>
                Choose <strong>Add to Home Screen</strong>, then tap <strong>Add</strong>.
              </li>
              <li>Open Guardian Mode from your Home Screen and come back here to turn on notifications.</li>
            </ol>
          </Notice>
        ) : support.kind === 'unsupported' && api.mode !== 'demo' ? (
          <Notice tone="info" title="Notifications aren’t available in this browser">
            {support.reason} Try Chrome, Edge, Firefox or Safari.
          </Notice>
        ) : (
          <div className="push-row">
            <div className="grow">
              <strong>{enabledHere ? 'On for this device' : 'Off for this device'}</strong>
              <p className="small muted">{me.pushTokens.length > 0 ? `You get notifications on ${me.pushTokens.length} device${me.pushTokens.length === 1 ? '' : 's'}.` : 'You won’t hear about anything until you turn this on.'}</p>
            </div>
            {enabledHere ? (
              <Button variant="secondary" loading={busy} onClick={() => void disable()}>
                Turn off here
              </Button>
            ) : (
              <Button variant="primary" icon="bell" loading={busy} onClick={() => void enable()}>
                Turn on
              </Button>
            )}
          </div>
        )}
        <Switch label="Things that need you now" description="A remote-control app was installed, a long call with an unknown number, and similar. Always on." checked disabled onChange={() => undefined} />
        <Switch
          label="Good-to-know notes"
          description="A pause card was shown, a reminder wasn’t confirmed, the phone hasn’t been used today."
          checked={me.notify.notice === 'push'}
          onChange={(v) => void api.updateMyMember(familyId, me.uid, { notify: { urgent: 'push', notice: v ? 'push' : 'off' } }).catch((e) => toast(friendlyError(e), 'error'))}
        />
      </div>
    </Card>
  );
}

export function FamilySettings() {
  const { familyId = '' } = useParams();
  const { api } = useData();
  const user = useUser();
  const paths = usePaths();
  const navigate = useNavigate();
  const toast = useToast();
  const family = useFamily(familyId);
  const members = useMembers(familyId);
  const devices = useDevices(familyId);
  const [inviting, setInviting] = useState(false);
  const [removing, setRemoving] = useState<Member | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [confirmText, setConfirmText] = useState('');
  const [busy, setBusy] = useState(false);
  const [profile, setProfile] = useState<{ displayName: string; phone: string } | null>(null);

  if (family.loading || members.loading) return <Spinner />;
  if (!family.data) return null;
  const fam = family.data;
  const list = members.data ?? [];
  const me = list.find((m) => m.uid === user.uid);
  const isOwner = fam.ownerUid === user.uid;
  const maxHelpers = fam.plan === 'family' ? 4 : 1;
  const canInvite = list.length < maxHelpers;
  const parent = devices.data?.[0]?.parentName ?? 'your parent';
  const form = profile ?? { displayName: me?.displayName ?? '', phone: me?.phone ?? '' };

  async function removeHelper(m: Member) {
    setBusy(true);
    try {
      await api.removeHelper({ familyId, uid: m.uid });
      toast(`${m.displayName} was removed.`);
      setRemoving(null);
    } catch (e) {
      toast(friendlyError(e), 'error');
    } finally {
      setBusy(false);
    }
  }

  async function deleteFamily() {
    setBusy(true);
    try {
      await api.deleteFamily({ familyId });
      try {
        localStorage.removeItem('gm.lastFamily');
      } catch {
        /* ignore */
      }
      toast('Your family and all its data were deleted.');
      navigate(paths.home(), { replace: true });
    } catch (e) {
      toast(friendlyError(e), 'error');
      setBusy(false);
    }
  }

  return (
    <div className="page">
      <header className="page-header">
        <p className="eyebrow">{fam.name}</p>
        <h1 className="h-page">Family settings</h1>
      </header>

      <div className="settings-grid">
        <Card>
          <CardHeader
            title="Helpers"
            description={`Up to ${maxHelpers} on your plan. ${parent} can see who you all are, and remove anyone.`}
            action={
              <Button variant="primary" icon="plus" disabled={!canInvite} onClick={() => setInviting(true)}>
                Invite
              </Button>
            }
          />
          <ul className="people">
            {list.map((m) => (
              <li key={m.uid} className="person">
                <Avatar name={m.displayName} src={m.photoUrl} size={48} />
                <div className="grow">
                  <div className="person-name">
                    {m.displayName}
                    {m.uid === user.uid ? <span className="subtle"> (you)</span> : null}
                    {m.role === 'owner' ? <Badge tone="brand">Owner</Badge> : null}
                    {m.status === 'pending' ? <Badge tone="notice">Waiting for approval</Badge> : null}
                  </div>
                  <div className="person-meta">
                    {m.status === 'pending' ? `${parent} needs to approve them on the phone` : [m.email, m.phone].filter(Boolean).join(' · ')}
                  </div>
                </div>
                {isOwner && m.uid !== user.uid ? <IconButton icon="trash" variant="danger" label={`Remove ${m.displayName}`} onClick={() => setRemoving(m)} /> : null}
              </li>
            ))}
          </ul>
          {!canInvite ? <p className="small subtle">You’ve reached the helper limit for your plan.</p> : null}
        </Card>

        {me ? (
          <Card>
            <CardHeader title="You" description="How the family and the phone see you." />
            <form
              className="stack"
              onSubmit={async (e) => {
                e.preventDefault();
                try {
                  await api.updateMyMember(familyId, me.uid, { displayName: form.displayName.trim(), phone: form.phone.trim() });
                  setProfile(null);
                  toast('Saved.');
                } catch (err) {
                  toast(friendlyError(err), 'error');
                }
              }}
            >
              <Field label="Name">{(ids) => <Input {...ids} maxLength={60} value={form.displayName} onChange={(e) => setProfile({ ...form, displayName: e.target.value })} />}</Field>
              <Field label="Phone number" hint="Used when adding you to the family list on the phone.">
                {(ids) => <Input {...ids} type="tel" maxLength={32} value={form.phone} onChange={(e) => setProfile({ ...form, phone: e.target.value })} />}
              </Field>
              <div>
                <Button type="submit" variant="secondary" disabled={!profile || !form.displayName.trim()}>
                  Save
                </Button>
              </div>
            </form>
          </Card>
        ) : null}

        {me ? <NotificationsCard familyId={familyId} me={me} /> : null}

        <Card>
          <CardHeader title="Plan" />
          <div className="plans">
            <div className={['plan', fam.plan === 'free' ? 'is-current' : ''].join(' ')}>
              <div className="row spread">
                <h3>Free</h3>
                {fam.plan === 'free' ? <Badge tone="brand">Your plan</Badge> : null}
              </div>
              <p className="plan-price">$0</p>
              <ul className="plan-list">
                <li>Simple home screen, set up remotely</li>
                <li>Photo card</li>
                <li>Unknown caller screening</li>
                <li>1 helper, 1 phone</li>
              </ul>
            </div>
            <div className={['plan', 'plan-featured', fam.plan === 'family' ? 'is-current' : ''].join(' ')}>
              <div className="row spread">
                <h3>Family</h3>
                {fam.plan === 'family' ? <Badge tone="brand">Your plan</Badge> : null}
              </div>
              <p className="plan-price">
                $5.99<span>/month</span>
              </p>
              <p className="small muted">or $49 a year</p>
              <ul className="plan-list">
                <li>Everything in Free</li>
                <li>Pause cards and all protection notices</li>
                <li>Up to 4 helpers and 2 parent phones</li>
                <li>Reminders that tell you if missed</li>
                <li>Help screenshots</li>
              </ul>
              {fam.plan === 'free' ? (
                <Button variant="primary" block disabled>
                  Upgrade · coming soon
                </Button>
              ) : null}
            </div>
          </div>
        </Card>

        {isOwner ? (
          <Card className="danger-zone">
            <CardHeader title="Delete family and data" description={`Deletes the family, every phone’s settings, alerts and photos, and disconnects ${parent}’s phone. This can’t be undone.`} />
            <Button variant="danger" icon="trash" onClick={() => setDeleting(true)}>
              Delete family
            </Button>
          </Card>
        ) : null}
      </div>

      {inviting ? <InviteDialog familyId={familyId} onClose={() => setInviting(false)} /> : null}

      <Dialog
        open={!!removing}
        onClose={() => setRemoving(null)}
        size="sm"
        title={`Remove ${removing?.displayName ?? ''}?`}
        description="They’ll stop getting notifications and won’t be able to see or change anything."
        footer={
          <>
            <Button variant="ghost" onClick={() => setRemoving(null)}>
              Cancel
            </Button>
            <Button variant="danger" className="btn-solid" loading={busy} onClick={() => removing && void removeHelper(removing)}>
              Remove
            </Button>
          </>
        }
      />

      <Dialog
        open={deleting}
        onClose={() => setDeleting(false)}
        size="sm"
        title="Delete everything?"
        description={`Type the family name, “${fam.name}”, to confirm.`}
        footer={
          <>
            <Button variant="ghost" onClick={() => setDeleting(false)}>
              Cancel
            </Button>
            <Button variant="danger" className="btn-solid" loading={busy} disabled={confirmText.trim() !== fam.name} onClick={() => void deleteFamily()}>
              Delete forever
            </Button>
          </>
        }
      >
        <Field label="Family name">{(ids) => <Input {...ids} value={confirmText} onChange={(e) => setConfirmText(e.target.value)} autoComplete="off" />}</Field>
      </Dialog>
    </div>
  );
}
