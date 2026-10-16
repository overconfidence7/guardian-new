import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { friendlyError } from '../data/api';
import { useData } from '../data/DataProvider';
import { Button } from '../components/Button';
import { Field, Input } from '../components/Field';
import { GoogleMark, Icon, type IconName } from '../components/Icon';
import { Wordmark } from '../components/Logo';
import { Notice } from '../components/EmptyState';
import { PhonePreview } from '../components/PhonePreview';
import { parseDeviceConfig } from '../lib/model';
import fixture from '../../../shared/fixtures/device-config.example.json';
import { DEMO_PHOTOS } from '../data/demoArt';

const previewConfig = parseDeviceConfig(fixture);

const POINTS: { icon: IconName; title: string; body: string }[] = [
  {
    icon: 'home',
    title: 'Simple, set up from anywhere',
    body: 'Big photo tiles for the people they love. Change the home screen from your phone and it updates on theirs in seconds.',
  },
  {
    icon: 'pause',
    title: 'A pause at the right moment',
    body: 'If a bank or gift-card app opens during or just after a call from an unknown number, a calm card asks them to call you first.',
  },
  {
    icon: 'heart',
    title: 'Their phone, their choice',
    body: 'They agree to every feature, can see exactly what the family sees, and can switch any part off. No messages, locations or call lists, ever.',
  },
];

export function SignInPanel({ intro, continuePath }: { intro?: string; continuePath?: string }) {
  const { api } = useData();
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState<null | 'google' | 'email' | 'dev'>(null);
  const [sentTo, setSentTo] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [devEmail, setDevEmail] = useState('sarah@example.com');
  const [devPassword, setDevPassword] = useState('password');

  async function run(kind: 'google' | 'email' | 'dev', fn: () => Promise<void>) {
    setBusy(kind);
    setError(null);
    try {
      await fn();
    } catch (e) {
      setError(friendlyError(e));
    } finally {
      setBusy(null);
    }
  }

  function onEmail(e: FormEvent) {
    e.preventDefault();
    const value = email.trim();
    if (!/^\S+@\S+\.\S+$/.test(value)) {
      setError('That email address doesn’t look right.');
      return;
    }
    void run('email', async () => {
      await api.auth.sendEmailLink(value, continuePath ?? window.location.pathname);
      setSentTo(value);
    });
  }

  return (
    <div className="signin card card-padded">
      <h2 className="signin-title">{intro ?? 'Get started'}</h2>
      <p className="muted">For family members helping with a parent’s phone. It’s free to start.</p>

      <Button variant="secondary" size="lg" block onClick={() => void run('google', () => api.auth.signInWithGoogle())} loading={busy === 'google'}>
        <span className="row nowrap" style={{ ['--gap' as string]: '10px' }}>
          <GoogleMark />
          Continue with Google
        </span>
      </Button>

      <div className="or" role="separator">
        <span>or</span>
      </div>

      {sentTo ? (
        <Notice tone="ok" icon="mail" title="Check your email">
          We sent a sign-in link to <strong>{sentTo}</strong>. Open it on this device to continue.{' '}
          <button type="button" className="link-btn" onClick={() => setSentTo(null)}>
            Use a different email
          </button>
        </Notice>
      ) : (
        <form className="stack" onSubmit={onEmail} noValidate>
          <Field label="Email me a sign-in link">
            {(ids) => <Input {...ids} type="email" inputMode="email" autoComplete="email" placeholder="you@example.com" value={email} onChange={(e) => setEmail(e.target.value)} />}
          </Field>
          <Button type="submit" variant="primary" size="lg" block loading={busy === 'email'}>
            Send link
          </Button>
        </form>
      )}

      {error ? (
        <p className="field-error" role="alert">
          {error}
        </p>
      ) : null}

      {api.auth.devSignIn ? (
        <details className="dev-signin">
          <summary>Developer sign-in (emulators only)</summary>
          <form
            className="stack"
            onSubmit={(e) => {
              e.preventDefault();
              void run('dev', () => api.auth.devSignIn!(devEmail.trim(), devPassword));
            }}
          >
            <Field label="Email">{(ids) => <Input {...ids} name="dev-email" type="email" value={devEmail} onChange={(e) => setDevEmail(e.target.value)} />}</Field>
            <Field label="Password">{(ids) => <Input {...ids} name="dev-password" type="password" value={devPassword} onChange={(e) => setDevPassword(e.target.value)} />}</Field>
            <Button type="submit" variant="quiet" block loading={busy === 'dev'}>
              Sign in with password
            </Button>
          </form>
        </details>
      ) : null}

      <p className="xsmall subtle signin-legal">By continuing you agree to our Terms and Privacy Policy. We never sell data, and never see messages, calls or locations.</p>
    </div>
  );
}

export function Landing() {
  const { base } = useData();
  return (
    <div className="landing">
      <header className="landing-header">
        <Wordmark />
        {base ? null : (
          <Link to="/demo" className="landing-demo-link">
            See a demo <Icon name="arrowRight" size={18} />
          </Link>
        )}
      </header>
      <main id="main" className="landing-main">
        <section className="landing-hero">
          <p className="eyebrow">For the family of someone 70+ with an Android phone</p>
          <h1 className="landing-title">Your parent’s phone, with your family in its corner.</h1>
          <p className="landing-lede">
            Guardian Mode turns Mom’s Android into a calm, simple phone you can set up from anywhere, and slows a scam down at the moments scams actually happen.
          </p>
          <div className="landing-signin">
            <SignInPanel />
          </div>
        </section>
        <aside className="landing-visual" aria-label="What Mom sees">
          <PhonePreview
            config={previewConfig}
            photo={{ url: DEMO_PHOTOS.school, fromName: 'Sarah', caption: 'Emma’s first day of second grade!' }}
            now={new Date(2026, 9, 16, 9, 41)}
            label="Example: a parent's home screen with big photo tiles"
          />
        </aside>
      </main>
      <section className="landing-points" aria-label="How it helps">
        {POINTS.map((p) => (
          <article key={p.title} className="point">
            <span className="point-icon">
              <Icon name={p.icon} size={24} />
            </span>
            <h2 className="point-title">{p.title}</h2>
            <p className="muted">{p.body}</p>
          </article>
        ))}
      </section>
      <footer className="landing-footer">
        <p className="small subtle">Guardian Mode · Made for families · Helpers use any phone or computer; the parent’s phone needs Android 10 or newer.</p>
      </footer>
    </div>
  );
}
