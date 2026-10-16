import { useEffect, useState, type FormEvent } from 'react';
import { useNavigate, useParams } from 'react-router';
import { friendlyError, type MembershipState } from '../data/api';
import { useData, usePaths } from '../data/DataProvider';
import { Button } from '../components/Button';
import { Field, Input } from '../components/Field';
import { Notice, Spinner } from '../components/EmptyState';
import { Icon } from '../components/Icon';
import { Wordmark } from '../components/Logo';
import { formatCode, isValidCode, normalizeCode } from '../lib/ids';
import { SignInPanel } from './Landing';

const PENDING_KEY = 'gm.pendingJoin';

/** /join/:code — accept a helper invite, then wait for the parent's approval on the phone. */
export function JoinScreen() {
  const { code: rawCode = '' } = useParams();
  const code = normalizeCode(rawCode);
  const { api, user, authReady } = useData();
  const paths = usePaths();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [joined, setJoined] = useState<{ familyId: string } | null>(() => {
    try {
      const saved = JSON.parse(sessionStorage.getItem(PENDING_KEY) ?? 'null') as { code: string; familyId: string } | null;
      return saved && saved.code === code ? { familyId: saved.familyId } : null;
    } catch {
      return null;
    }
  });
  const [membership, setMembership] = useState<MembershipState>('pending');

  useEffect(() => {
    if (!joined || !user) return;
    return api.watchMyMembership(joined.familyId, user.uid, setMembership);
  }, [api, joined, user]);

  useEffect(() => {
    if (membership !== 'active' || !joined) return;
    try {
      sessionStorage.removeItem(PENDING_KEY);
    } catch {
      /* ignore */
    }
    const t = setTimeout(() => navigate(paths.family(joined.familyId), { replace: true }), 1800);
    return () => clearTimeout(t);
  }, [membership, joined, navigate, paths]);

  const valid = isValidCode(code);
  const displayName = name || user?.displayName || '';

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!displayName.trim()) {
      setError('Please tell the family your name.');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const res = await api.acceptHelperInvite({ code, displayName: displayName.trim(), ...(phone.trim() ? { phone: phone.trim() } : {}) });
      if (res.status === 'active') {
        navigate(paths.family(res.familyId), { replace: true });
        return;
      }
      try {
        sessionStorage.setItem(PENDING_KEY, JSON.stringify({ code, familyId: res.familyId }));
      } catch {
        /* ignore */
      }
      setJoined({ familyId: res.familyId });
    } catch (err) {
      setError(friendlyError(err));
    } finally {
      setBusy(false);
    }
  }

  let body;
  if (!valid) {
    body = (
      <Notice tone="notice" title="This invite link looks incomplete">
        Ask the person who invited you to send the link again.
      </Notice>
    );
  } else if (!authReady) {
    body = <Spinner />;
  } else if (!user) {
    body = (
      <>
        <header className="page-header center-text">
          <h1 className="h-page">You’re invited to help</h1>
          <p className="lede">Sign in to join your family on Guardian Mode. Invite code {formatCode(code)}.</p>
        </header>
        <SignInPanel intro="Sign in to accept" continuePath={`/join/${code}`} />
      </>
    );
  } else if (joined && membership === 'active') {
    body = (
      <div className="card card-padded connected" role="status">
        <span className="connected-badge" aria-hidden="true">
          <Icon name="checkCircle" size={44} />
        </span>
        <h1 className="h-page">You’re in!</h1>
        <p className="lede">Taking you to your family…</p>
      </div>
    );
  } else if (joined && membership === 'declined') {
    body = (
      <Notice tone="notice" title="Not this time">
        The invite wasn’t approved on the phone. If that seems wrong, talk with the family and ask for a new invite.
      </Notice>
    );
  } else if (joined) {
    body = (
      <div className="card card-padded waiting-card" role="status" aria-live="polite">
        <span className="waiting-phone" aria-hidden="true">
          <Icon name="smartphone" size={40} />
          <span className="pulse" />
        </span>
        <h1 className="h-page">Waiting for approval on the phone</h1>
        <p className="lede">
          For everyone’s safety, your parent approves each new helper on their own phone. They’ll see “Allow {displayName.split(' ')[0]} to help?” next time they pick it up.
        </p>
        <p className="muted small">You can close this page. We’ll let you in as soon as they say yes.</p>
      </div>
    );
  } else {
    body = (
      <>
        <header className="page-header center-text">
          <h1 className="h-page">Join your family</h1>
          <p className="lede">You’ll be able to help with the phone settings and get a heads-up when something needs you.</p>
        </header>
        <form className="card card-padded stack" style={{ ['--gap' as string]: 'var(--s-5)' }} onSubmit={submit} noValidate>
          <Field label="Your name" hint="As your parent knows you, like “David”.">
            {(ids) => <Input {...ids} name="displayName" autoComplete="name" value={displayName} onChange={(e) => setName(e.target.value)} />}
          </Field>
          <Field label="Your phone number" optional hint="So they can call you from their home screen.">
            {(ids) => <Input {...ids} name="phone" type="tel" inputMode="tel" autoComplete="tel" value={phone} onChange={(e) => setPhone(e.target.value)} />}
          </Field>
          {error ? (
            <p className="field-error" role="alert">
              {error}
            </p>
          ) : null}
          <Button type="submit" variant="primary" size="lg" block loading={busy}>
            Join the family
          </Button>
          <p className="small subtle">Your parent will be asked to approve you on their phone.</p>
        </form>
      </>
    );
  }

  return (
    <div className="centered-page">
      <Wordmark />
      <div className="page-narrow stack" style={{ width: '100%' }}>
        {body}
      </div>
    </div>
  );
}
