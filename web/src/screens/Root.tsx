import { useEffect, useState } from 'react';
import { Navigate, Outlet, useNavigate, useSearchParams } from 'react-router';
import { friendlyError } from '../data/api';
import { useData, usePaths } from '../data/DataProvider';
import { useMyFamilies } from '../data/hooks';
import { Spinner } from '../components/EmptyState';
import { Button } from '../components/Button';
import { Field, Input } from '../components/Field';
import { Wordmark } from '../components/Logo';
import { Landing } from './Landing';

/** Shows the landing/sign-in page in place until someone is signed in. */
export function RequireAuth() {
  const { user, authReady } = useData();
  if (!authReady) return <Spinner />;
  if (!user) return <Landing />;
  return <Outlet />;
}

/** "/": the landing page, or the right family for a signed-in helper. */
export function RootScreen() {
  const { user, authReady } = useData();
  const paths = usePaths();
  const families = useMyFamilies();
  if (!authReady) return <Spinner />;
  if (!user) return <Landing />;
  if (families.error) return <Navigate to={paths.welcome()} replace />;
  if (!families.data) return <Spinner label="Loading your family" />;
  if (families.data.length === 0) return <Navigate to={paths.welcome()} replace />;
  let last: string | null = null;
  try {
    last = localStorage.getItem('gm.lastFamily');
  } catch {
    /* ignore */
  }
  const target = families.data.find((f) => f.id === last) ?? families.data[0]!;
  return <Navigate to={paths.family(target.id)} replace />;
}

/** Landing target of the email sign-in link. */
export function FinishSignIn() {
  const { api, user, authReady } = useData();
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [state, setState] = useState<'working' | 'need-email' | 'error'>('working');
  const [email, setEmail] = useState('');
  const [error, setError] = useState<string | null>(null);
  const next = params.get('next') || '/';
  const safeNext = next.startsWith('/') && !next.startsWith('//') ? next : '/';

  useEffect(() => {
    let alive = true;
    api.auth.completeEmailLink().then(
      (r) => {
        if (!alive) return;
        if (r === 'need-email') setState('need-email');
        else navigate(safeNext, { replace: true });
      },
      (e) => {
        if (!alive) return;
        setError(friendlyError(e));
        setState('error');
      },
    );
    return () => {
      alive = false;
    };
  }, [api, navigate, safeNext]);

  if (authReady && user && state === 'working') return <Navigate to={safeNext} replace />;

  return (
    <div className="centered-page">
      <Wordmark />
      {state === 'working' ? <Spinner label="Signing you in" /> : null}
      {state === 'need-email' ? (
        <form
          className="card card-padded stack"
          onSubmit={async (e) => {
            e.preventDefault();
            try {
              await api.auth.completeEmailLink(email.trim());
              navigate(safeNext, { replace: true });
            } catch (err) {
              setError(friendlyError(err));
            }
          }}
        >
          <h1 className="h-page">Confirm your email</h1>
          <p className="muted">You opened the link on a different device. Enter the same email address to finish signing in.</p>
          <Field label="Email" error={error}>
            {(ids) => <Input {...ids} type="email" autoComplete="email" value={email} onChange={(e) => setEmail(e.target.value)} />}
          </Field>
          <Button type="submit" variant="primary" size="lg" block>
            Continue
          </Button>
        </form>
      ) : null}
      {state === 'error' ? (
        <div className="card card-padded stack">
          <h1 className="h-page">That link didn’t work</h1>
          <p className="muted">{error} Links work once and expire after a while. Request a new one.</p>
          <Button variant="primary" onClick={() => navigate('/', { replace: true })}>
            Back to sign in
          </Button>
        </div>
      ) : null}
    </div>
  );
}

export function NotFound() {
  const paths = usePaths();
  return (
    <div className="centered-page">
      <Wordmark />
      <div className="card card-padded stack">
        <h1 className="h-page">We couldn’t find that page</h1>
        <p className="muted">The link may be old, or you may not be part of that family yet.</p>
        <Button variant="primary" onClick={() => window.location.assign(paths.home())}>
          Go home
        </Button>
      </div>
    </div>
  );
}
