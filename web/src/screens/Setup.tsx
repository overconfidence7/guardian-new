import { useCallback, useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router';
import QRCode from 'qrcode';
import { friendlyError } from '../data/api';
import { useData, usePaths } from '../data/DataProvider';
import { useDevice, useNow } from '../data/hooks';
import { Button, ButtonLink } from '../components/Button';
import { Notice, Spinner } from '../components/EmptyState';
import { Icon } from '../components/Icon';
import { formatCode } from '../lib/ids';
import { formatCountdown } from '../lib/time';
import type { Invite } from '../lib/types';

function QR({ value, label }: { value: string; label: string }) {
  const [svg, setSvg] = useState<string | null>(null);
  useEffect(() => {
    let alive = true;
    QRCode.toString(value, { type: 'svg', errorCorrectionLevel: 'M', margin: 1, color: { dark: '#14302b', light: '#ffffff' } }).then(
      (s) => alive && setSvg(s),
      () => undefined,
    );
    return () => {
      alive = false;
    };
  }, [value]);
  return <div className="qr" role="img" aria-label={label} dangerouslySetInnerHTML={svg ? { __html: svg } : undefined} />;
}

const STEPS = (parent: string) => [
  { title: `Sit with ${parent}`, body: `Do this together, in person. ${parent} taps every “yes” on their own phone.` },
  { title: 'Install Guardian Mode', body: 'On their Android phone, open the Google Play Store and search for “Guardian Mode”. Tap Install.' },
  { title: 'Open it and choose “Set up with my family”', body: 'Then type the code below, or tap “Scan code” and point the phone at this screen.' },
  { title: `${parent} says yes to each feature`, body: 'One short screen per feature explains what you’ll see and what you never see. Help with the switches if needed.' },
];

/** Step 2: show a short-lived setup code + QR, and celebrate when the phone connects. */
export function SetupScreen() {
  const { familyId = '', deviceId = '' } = useParams();
  const { api } = useData();
  const paths = usePaths();
  const device = useDevice(familyId, deviceId);
  const now = useNow(1000);
  const [invite, setInvite] = useState<Invite | null>(null);
  const [state, setState] = useState<'idle' | 'loading' | 'error'>('idle');
  const [error, setError] = useState<string | null>(null);
  const [started, setStarted] = useState(false);
  const connectedRef = useRef<HTMLHeadingElement>(null);

  const parent = device.data?.parentName || 'your parent';
  const connected = !!device.data?.deviceUid;

  const newCode = useCallback(async () => {
    setState('loading');
    setError(null);
    try {
      setInvite(await api.createDeviceInvite({ familyId, deviceId }));
      setState('idle');
    } catch (e) {
      setError(friendlyError(e));
      setState('error');
    }
  }, [api, familyId, deviceId]);

  useEffect(() => {
    if (connected) connectedRef.current?.focus();
  }, [connected]);

  if (device.loading) return <Spinner />;
  if (!device.data) {
    return (
      <div className="page page-narrow">
        <Notice tone="notice" title="This phone was removed">
          It may have been removed by another helper.
        </Notice>
      </div>
    );
  }

  if (connected) {
    return (
      <div className="page page-narrow">
        <div className="card card-padded connected">
          <span className="connected-badge" aria-hidden="true">
            <Icon name="checkCircle" size={44} />
          </span>
          <h1 className="h-page" tabIndex={-1} ref={connectedRef}>
            Connected!
          </h1>
          <p className="lede" role="status">
            {parent}’s phone is now part of your family. Finish the steps on their phone, then come back to set up their home screen.
          </p>
          <div className="row center">
            <ButtonLink to={paths.device(familyId, deviceId, 'home')} variant="primary" size="lg" iconAfter="arrowRight">
              Set up the home screen
            </ButtonLink>
            <ButtonLink to={paths.family(familyId)} variant="ghost" size="lg">
              Go to family home
            </ButtonLink>
          </div>
        </div>
      </div>
    );
  }

  const remaining = invite ? invite.expiresAt - now : 0;
  const expired = !!invite && remaining <= 0;
  const codeShown = invite && !expired;

  return (
    <div className="page setup">
      <ol className="steps" aria-label="Setup steps">
        <li className="is-done">
          <span>
            <Icon name="check" size={16} />
          </span>{' '}
          Your family
        </li>
        <li className="is-current" aria-current="step">
          <span>2</span> Connect {parent}’s phone
        </li>
      </ol>
      <header className="page-header">
        <h1 className="h-page">Connect {parent}’s phone</h1>
        <p className="lede">Setup codes last 15 minutes, so get one when you’re sitting together with {parent}’s phone in hand.</p>
      </header>

      <div className="setup-grid">
        <section className="card card-padded" aria-labelledby="how-title">
          <h2 id="how-title" className="card-title">
            How it works
          </h2>
          <ol className="howto">
            {STEPS(parent).map((s, i) => (
              <li key={s.title}>
                <span className="howto-num" aria-hidden="true">
                  {i + 1}
                </span>
                <div>
                  <h3 className="howto-title">{s.title}</h3>
                  <p className="muted">{s.body}</p>
                </div>
              </li>
            ))}
          </ol>
        </section>

        <section className="card card-padded code-card" aria-labelledby="code-title" aria-live="polite">
          <h2 id="code-title" className="card-title">
            Setup code
          </h2>
          {!started && !invite ? (
            <div className="code-start stack">
              <p className="muted">Are you with {parent} now?</p>
              <Button
                variant="primary"
                size="lg"
                block
                icon="qr"
                loading={state === 'loading'}
                onClick={() => {
                  setStarted(true);
                  void newCode();
                }}
              >
                Yes, show the code
              </Button>
              <p className="small subtle">Not together yet? No problem: come back to this page when you are. Find it on your family home.</p>
            </div>
          ) : state === 'loading' && !invite ? (
            <Spinner label="Making a code" />
          ) : state === 'error' ? (
            <Notice tone="notice" title="We couldn’t make a code" action={<Button onClick={() => void newCode()}>Try again</Button>}>
              {error}
            </Notice>
          ) : codeShown ? (
            <div className="code-body">
              <p className="code" aria-label={`Setup code: ${invite.code.split('').join(' ')}`} data-testid="setup-code">
                {formatCode(invite.code)}
              </p>
              <QR value={invite.code} label={`QR code for setup code ${invite.code}`} />
              <p className={['countdown', remaining < 60_000 ? 'is-low' : ''].join(' ')}>
                <Icon name="clock" size={18} /> Works for <strong className="mono">{formatCountdown(remaining)}</strong>
                <span className="sr-only"> minutes</span>
              </p>
              <p className="waiting">
                <span className="pulse" aria-hidden="true" /> Waiting for {parent}’s phone…
              </p>
              <Button variant="ghost" icon="history" onClick={() => void newCode()} loading={state === 'loading'}>
                Get a new code
              </Button>
            </div>
          ) : (
            <div className="code-start stack">
              <Notice tone="info" icon="clock" title="That code has expired">
                Codes last 15 minutes. Get a fresh one when you’re ready.
              </Notice>
              <Button variant="primary" size="lg" block icon="history" onClick={() => void newCode()} loading={state === 'loading'}>
                Get a new code
              </Button>
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
