import { useMemo, useState, type ReactNode } from 'react';
import { Link } from 'react-router';
import { usePaths } from '../../data/DataProvider';
import { Button, IconButton } from '../../components/Button';
import { Card } from '../../components/Card';
import { Dialog } from '../../components/Dialog';
import { Spinner } from '../../components/EmptyState';
import { Checkbox, Field, Input, RadioCards, Select, Slider } from '../../components/Field';
import { Icon, type IconName } from '../../components/Icon';
import { Switch } from '../../components/Switch';
import { builtInCategory, CATEGORY_LABELS } from '../../lib/appCatalog';
import { APP_CATEGORIES, type AppCategory, type ScreeningMode } from '../../lib/model';
import type { Device } from '../../lib/types';
import { useConfigEditor } from './ConfigEditor';

const PAUSE_CATEGORIES = APP_CATEGORIES.filter((c) => c !== 'REMOTE_ACCESS');

function Feature({ icon, title, intro, shares, never, on, children, id }: { icon: IconName; title: string; intro: ReactNode; shares: string; never: string; on: boolean; children: ReactNode; id: string }) {
  return (
    <Card className={['feature', on ? 'is-on' : 'is-off'].join(' ')} aria-labelledby={id}>
      <div className="feature-head">
        <span className="feature-icon" aria-hidden="true">
          <Icon name={icon} size={24} />
        </span>
        <div className="grow">
          <h2 id={id} className="card-title">
            {title}
          </h2>
          <p className="muted">{intro}</p>
        </div>
      </div>
      <div className="feature-body">{children}</div>
      <dl className="feature-privacy">
        <div>
          <dt>
            <Icon name="eye" size={16} /> You’ll see
          </dt>
          <dd>{shares}</dd>
        </div>
        <div>
          <dt>
            <Icon name="lock" size={16} /> Never shared
          </dt>
          <dd>{never}</dd>
        </div>
      </dl>
    </Card>
  );
}

function timezones(): string[] {
  const common = ['America/New_York', 'America/Chicago', 'America/Denver', 'America/Phoenix', 'America/Los_Angeles', 'America/Anchorage', 'Pacific/Honolulu'];
  let all: string[] = [];
  try {
    all = (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone') ?? [];
  } catch {
    /* older browsers */
  }
  return [...common, ...all.filter((z) => !common.includes(z))];
}

function tzLabel(tz: string): string {
  try {
    const name = new Intl.DateTimeFormat('en-US', { timeZone: tz, timeZoneName: 'long' }).formatToParts(new Date()).find((p) => p.type === 'timeZoneName')?.value;
    return `${tz.replace(/_/g, ' ')}${name ? ` (${name})` : ''}`;
  } catch {
    return tz;
  }
}

function ExtraAppDialog({ device, existing, onClose, onAdd }: { device: Device; existing: Record<string, AppCategory>; onClose: () => void; onAdd: (pkg: string, cat: AppCategory) => void }) {
  const [pkg, setPkg] = useState('');
  const [cat, setCat] = useState<AppCategory>('BANK');
  const candidates = device.installedApps.filter((a) => !existing[a.packageName] && !builtInCategory(a.packageName)).sort((a, b) => a.label.localeCompare(b.label));
  return (
    <Dialog
      open
      onClose={onClose}
      title="Watch another app"
      description="For money apps we don’t know yet, like a local credit union."
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" disabled={!pkg} onClick={() => onAdd(pkg, cat)}>
            Add
          </Button>
        </>
      }
    >
      {candidates.length === 0 ? (
        <p className="muted">Every app on the phone is either watched already or not reported yet.</p>
      ) : (
        <Field label="App on the phone">
          {(ids) => (
            <Select {...ids} value={pkg} onChange={(e) => setPkg(e.target.value)}>
              <option value="">Choose an app…</option>
              {candidates.map((a) => (
                <option key={a.packageName} value={a.packageName}>
                  {a.label}
                </option>
              ))}
            </Select>
          )}
        </Field>
      )}
      <Field label="What kind of app is it?">
        {(ids) => (
          <Select {...ids} value={cat} onChange={(e) => setCat(e.target.value as AppCategory)}>
            {PAUSE_CATEGORIES.map((c) => (
              <option key={c} value={c}>
                {CATEGORY_LABELS[c].label}
              </option>
            ))}
          </Select>
        )}
      </Field>
    </Dialog>
  );
}

export function ProtectionTab({ familyId, device }: { familyId: string; device: Device }) {
  const { config, update, loading } = useConfigEditor();
  const paths = usePaths();
  const [showWord, setShowWord] = useState(false);
  const [addingApp, setAddingApp] = useState(false);
  const zones = useMemo(timezones, []);
  if (loading || !config) return <Spinner />;

  const p = config.protection;
  const parent = device.parentName;
  const label = (pkg: string) => device.installedApps.find((a) => a.packageName === pkg)?.label ?? pkg;
  const watchedInstalled = device.installedApps.filter((a) => {
    const c = builtInCategory(a.packageName);
    return c && c !== 'REMOTE_ACCESS';
  });
  const remoteInstalled = device.installedApps.filter((a) => builtInCategory(a.packageName) === 'REMOTE_ACCESS' || p.remoteAccessAlarm.trustedPackages.includes(a.packageName));

  return (
    <div className="stack" style={{ ['--gap' as string]: 'var(--s-5)' }}>
      <p className="lede">
        Each feature was agreed to on {parent}’s phone, and {parent} can switch any of them off. Everything here is explained to them in the same words.
      </p>

      <Feature
        id="f-pause"
        icon="pause"
        title="Pause before sending money"
        on={p.pauseTripwires.enabled}
        intro={`If a bank, payment, gift-card or crypto app opens during a call with an unknown number, or soon after, ${parent} sees a calm card: “Did someone on the phone ask you to do this?” with a big button to call family first.`}
        shares="That a pause card appeared, which app, and whether they chose to call family."
        never="Anything typed or shown inside the app."
      >
        <Switch label="Pause cards" checked={p.pauseTripwires.enabled} onChange={(v) => update((c) => void (c.protection.pauseTripwires.enabled = v))} />
        <fieldset className="stack" disabled={!p.pauseTripwires.enabled} style={{ ['--gap' as string]: 'var(--s-5)', border: 0, padding: 0, margin: 0 }}>
          <Slider
            label="Stay alert after a call for"
            value={p.pauseTripwires.windowMinutes}
            min={10}
            max={60}
            step={5}
            format={(v) => `${v} min`}
            hint="Scammers often call back or wait a few minutes. 30 minutes is a good balance."
            onChange={(v) => update((c) => void (c.protection.pauseTripwires.windowMinutes = v))}
          />
          <div>
            <p className="field-label">Which kinds of apps</p>
            {PAUSE_CATEGORIES.map((cat) => (
              <Checkbox
                key={cat}
                label={CATEGORY_LABELS[cat].plural}
                description={CATEGORY_LABELS[cat].description}
                checked={p.pauseTripwires.categories.includes(cat)}
                onChange={(v) =>
                  update((c) => {
                    const set = new Set(c.protection.pauseTripwires.categories);
                    if (v) set.add(cat);
                    else set.delete(cat);
                    c.protection.pauseTripwires.categories = APP_CATEGORIES.filter((x) => set.has(x));
                  })
                }
              />
            ))}
          </div>
          {watchedInstalled.length > 0 ? (
            <p className="small muted">
              <Icon name="checkCircle" size={16} className="inline-icon" /> Already watched on this phone: {watchedInstalled.map((a) => a.label).join(', ')}.
            </p>
          ) : null}
          <div className="stack" style={{ ['--gap' as string]: 'var(--s-2)' }}>
            <div className="row spread">
              <p className="field-label">Apps you added</p>
              <Button size="sm" variant="quiet" icon="plus" onClick={() => setAddingApp(true)}>
                Add an app
              </Button>
            </div>
            {Object.keys(p.pauseTripwires.extraPackages).length === 0 ? (
              <p className="small subtle">None. Add their local bank or credit union if it isn’t listed above.</p>
            ) : (
              <ul className="chips">
                {Object.entries(p.pauseTripwires.extraPackages).map(([pkg, cat]) => (
                  <li key={pkg} className="chip">
                    <span>
                      {label(pkg)} <span className="subtle">· {CATEGORY_LABELS[cat].label}</span>
                    </span>
                    <IconButton
                      icon="x"
                      size="sm"
                      label={`Stop watching ${label(pkg)}`}
                      onClick={() =>
                        update((c) => {
                          delete c.protection.pauseTripwires.extraPackages[pkg];
                        })
                      }
                    />
                  </li>
                ))}
              </ul>
            )}
          </div>
        </fieldset>
      </Feature>

      <Feature
        id="f-remote"
        icon="remote"
        title="Remote-control app warning"
        on={p.remoteAccessAlarm.enabled}
        intro={`Scammers ask people to install apps like AnyDesk or TeamViewer so they can take over the phone. When one is installed or opened, ${parent} sees a full-screen warning with your number, and you’re told right away.`}
        shares="The name of a remote-control app when it’s installed or opened."
        never="What happens inside that app."
      >
        <Switch label="Remote-control warning" checked={p.remoteAccessAlarm.enabled} onChange={(v) => update((c) => void (c.protection.remoteAccessAlarm.enabled = v))} />
        <Switch
          label="Also warn about new apps that can control the screen"
          description="A gentler warning for any newly installed app with screen-control abilities."
          checked={p.remoteAccessAlarm.warnOnControlCapableInstalls}
          disabled={!p.remoteAccessAlarm.enabled}
          onChange={(v) => update((c) => void (c.protection.remoteAccessAlarm.warnOnControlCapableInstalls = v))}
        />
        <div>
          <p className="field-label">Tools your family uses to help</p>
          <p className="field-hint">Trusted apps never trigger the warning. Only tick one you installed yourself.</p>
          {remoteInstalled.length === 0 ? (
            <p className="small subtle">No remote-control apps are on the phone. That’s good.</p>
          ) : (
            remoteInstalled.map((a) => (
              <Checkbox
                key={a.packageName}
                label={`Trust ${a.label}`}
                disabled={!p.remoteAccessAlarm.enabled}
                checked={p.remoteAccessAlarm.trustedPackages.includes(a.packageName)}
                onChange={(v) =>
                  update((c) => {
                    const set = new Set(c.protection.remoteAccessAlarm.trustedPackages);
                    if (v) set.add(a.packageName);
                    else set.delete(a.packageName);
                    c.protection.remoteAccessAlarm.trustedPackages = [...set];
                  })
                }
              />
            ))
          )}
        </div>
      </Feature>

      <Feature
        id="f-screening"
        icon="phone"
        title="Unknown caller screening"
        on={p.callScreening.mode !== 'OFF'}
        intro={`Most scams start with a call from a number ${parent} doesn’t know. Family, contacts and your “always ring” numbers always ring through.`}
        shares="Nothing. Screening happens on the phone only."
        never="Who calls, or their numbers."
      >
        <RadioCards<ScreeningMode>
          legend="When an unknown number calls"
          name="screening"
          value={p.callScreening.mode}
          onChange={(v) => update((c) => void (c.protection.callScreening.mode = v))}
          options={[
            { value: 'SILENCE_UNKNOWN', title: 'Ring silently', description: 'The phone doesn’t ring. The call goes to voicemail if not answered and still shows in the call log.', badge: 'Recommended' },
            { value: 'VOICEMAIL_UNKNOWN', title: 'Send to voicemail', description: 'Unknown callers go straight to voicemail. Real callers can leave a message.' },
            { value: 'OFF', title: 'Ring as usual', description: 'Every call rings. Guardian still notices unknown callers for the pause card.' },
          ]}
        />
        <Switch
          label="Let urgent callers through"
          description="If the same number calls twice within 3 minutes, the second call rings."
          checked={p.callScreening.allowRepeatCallers}
          disabled={p.callScreening.mode === 'OFF'}
          onChange={(v) => update((c) => void (c.protection.callScreening.allowRepeatCallers = v))}
        />
        <p className="small muted">
          {p.callScreening.allowlist.length} number{p.callScreening.allowlist.length === 1 ? '' : 's'} always ring. <Link to={paths.device(familyId, device.id, 'people')}>Edit in People</Link>
        </p>
      </Feature>

      <Feature
        id="f-longcall"
        icon="clock"
        title="Long calls with unknown numbers"
        on={p.longCallAlert.enabled}
        intro={`Scam calls tend to run long. If ${parent} has been on a call with an unknown number for a while, you’ll get one quiet heads-up so you can ring in.`}
        shares={`That a call with an unknown number went past ${p.longCallAlert.minutes} minutes.`}
        never="Who called, their number, or what was said. Calls are never recorded."
      >
        <Switch label="Long-call heads-up" checked={p.longCallAlert.enabled} onChange={(v) => update((c) => void (c.protection.longCallAlert.enabled = v))} />
        <Slider
          label="Tell me after"
          value={p.longCallAlert.minutes}
          min={10}
          max={60}
          step={5}
          format={(v) => `${v} min`}
          onChange={(v) => update((c) => void (c.protection.longCallAlert.minutes = v))}
        />
      </Feature>

      <Feature
        id="f-safeword"
        icon="lock"
        title="Family safe word"
        on={p.safeWord.enabled}
        intro={`After a call with an unknown number, a gentle card reminds ${parent}: if someone claims to be family in trouble, ask for the safe word.`}
        shares="Nothing."
        never="The safe word itself. It’s stored on the phone so they can look it up, and never sent back."
      >
        <Switch label="Safe word reminder" checked={p.safeWord.enabled} onChange={(v) => update((c) => void (c.protection.safeWord.enabled = v))} />
        <Field label="Our safe word" hint="Something easy to remember and hard to guess. Agree on it together, out loud." optional>
          {(ids) => (
            <div className="input-with-action">
              <Input
                {...ids}
                type={showWord ? 'text' : 'password'}
                autoComplete="off"
                spellCheck={false}
                value={p.safeWord.word}
                disabled={!p.safeWord.enabled}
                onChange={(e) => update((c) => void (c.protection.safeWord.word = e.target.value))}
              />
              <IconButton icon={showWord ? 'eyeOff' : 'eye'} label={showWord ? 'Hide safe word' : 'Show safe word'} onClick={() => setShowWord((s) => !s)} />
            </div>
          )}
        </Field>
      </Feature>

      <Feature
        id="f-checkin"
        icon="sun"
        title="Daily “I’m OK”"
        on={config.checkIn.enabled}
        intro={`No buttons for ${parent}. If the phone hasn’t been picked up by a time you choose, you’ll get a gentle note. You’ll also hear if it’s been silent (off or out of battery) for a whole day.`}
        shares={`Whether the phone has been used today, by ${config.checkIn.deadline}.`}
        never="Where they are or what they used the phone for."
      >
        <Switch label="Daily check-in" checked={config.checkIn.enabled} onChange={(v) => update((c) => void (c.checkIn.enabled = v))} />
        <div className="field-row">
          <Field label="Used by">
            {(ids) => (
              <Input {...ids} type="time" step={900} value={config.checkIn.deadline} disabled={!config.checkIn.enabled} onChange={(e) => e.target.value && update((c) => void (c.checkIn.deadline = e.target.value.slice(0, 5)))} />
            )}
          </Field>
          <Field label={`${parent}’s time zone`}>
            {(ids) => (
              <Select {...ids} value={config.checkIn.timezone} disabled={!config.checkIn.enabled} onChange={(e) => update((c) => void (c.checkIn.timezone = e.target.value))}>
                {(zones.includes(config.checkIn.timezone) ? zones : [config.checkIn.timezone, ...zones]).map((z) => (
                  <option key={z} value={z}>
                    {tzLabel(z)}
                  </option>
                ))}
              </Select>
            )}
          </Field>
        </div>
      </Feature>

      {addingApp ? (
        <ExtraAppDialog
          device={device}
          existing={p.pauseTripwires.extraPackages}
          onClose={() => setAddingApp(false)}
          onAdd={(pkg, cat) => {
            update((c) => void (c.protection.pauseTripwires.extraPackages[pkg] = cat));
            setAddingApp(false);
          }}
        />
      ) : null}
    </div>
  );
}
