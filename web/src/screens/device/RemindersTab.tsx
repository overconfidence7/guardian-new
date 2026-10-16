import { useState } from 'react';
import { Button, IconButton } from '../../components/Button';
import { Card } from '../../components/Card';
import { Dialog } from '../../components/Dialog';
import { Badge, EmptyState, Spinner } from '../../components/EmptyState';
import { Checkbox, Field, Input, RadioCards, Textarea } from '../../components/Field';
import { Icon } from '../../components/Icon';
import { newId } from '../../lib/ids';
import type { Reminder, ReminderKind } from '../../lib/model';
import { DAY_NAMES, describeDays, formatHHmm } from '../../lib/time';
import type { Device } from '../../lib/types';
import { useConfigEditor } from './ConfigEditor';

const KIND_ICON = { MEDICATION: 'pill', APPOINTMENT: 'calendar', OTHER: 'bell' } as const;

/** Toggle one ISO day; empty means every day, and at least one day stays selected. */
export function toggleDay(days: number[], day: number): number[] {
  const current = days.length === 0 ? [1, 2, 3, 4, 5, 6, 7] : days;
  const next = current.includes(day) ? current.filter((d) => d !== day) : [...current, day].sort((a, b) => a - b);
  if (next.length === 0) return current;
  return next.length === 7 ? [] : next;
}

export function describeSchedule(r: Reminder): string {
  const times = r.times.length ? r.times.map(formatHHmm).join(', ') : 'No time set';
  if (r.date) {
    const d = new Date(`${r.date}T12:00:00`);
    const day = Number.isNaN(d.getTime()) ? r.date : d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
    return `${day} at ${times}`;
  }
  return `${describeDays(r.daysOfWeek)} at ${times}`;
}

function ReminderDialog({ initial, parent, onClose, onSave }: { initial: Reminder; parent: string; onClose: () => void; onSave: (r: Reminder) => void }) {
  const [r, setR] = useState<Reminder>(initial);
  const [once, setOnce] = useState(!!initial.date);
  const [error, setError] = useState<string | null>(null);
  const isNew = !initial.title;

  function save() {
    if (!r.title.trim()) return setError('Give the reminder a short title.');
    const times = [...new Set(r.times.filter(Boolean))].sort();
    if (times.length === 0) return setError('Add at least one time.');
    if (once && !r.date) return setError('Pick the day.');
    onSave({ ...r, title: r.title.trim(), detail: r.detail.trim(), times, date: once ? r.date : null, daysOfWeek: once ? [] : r.daysOfWeek });
  }

  return (
    <Dialog
      open
      onClose={onClose}
      size="lg"
      title={isNew ? 'New reminder' : 'Edit reminder'}
      description={`At the set time, ${parent} sees a big card with Done and “Remind me in 10 minutes”.`}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button variant="primary" onClick={save}>
            {isNew ? 'Add reminder' : 'Done'}
          </Button>
        </>
      }
    >
      <RadioCards<ReminderKind>
        legend="Kind"
        name="kind"
        value={r.kind}
        onChange={(kind) => setR({ ...r, kind })}
        options={[
          { value: 'MEDICATION', title: 'Medication' },
          { value: 'APPOINTMENT', title: 'Appointment' },
          { value: 'OTHER', title: 'Something else' },
        ]}
      />
      <Field label="Title" hint="What the card says in big letters.">
        {(ids) => <Input {...ids} maxLength={60} value={r.title} onChange={(e) => setR({ ...r, title: e.target.value })} placeholder={r.kind === 'APPOINTMENT' ? 'Dr. Patel checkup' : 'Blood pressure pill'} />}
      </Field>
      <Field label="Details" optional>
        {(ids) => <Textarea {...ids} maxLength={160} value={r.detail} onChange={(e) => setR({ ...r, detail: e.target.value })} placeholder={r.kind === 'APPOINTMENT' ? 'Bring your pill list' : 'One white tablet with water'} />}
      </Field>

      <fieldset className="stack plain-fieldset" style={{ ['--gap' as string]: 'var(--s-3)' }}>
        <legend className="field-label">Times</legend>
        {r.times.map((t, i) => (
          <div key={i} className="row nowrap">
            <Input
              type="time"
              aria-label={`Time ${i + 1}`}
              value={t}
              onChange={(e) => {
                const times = r.times.slice();
                times[i] = e.target.value.slice(0, 5);
                setR({ ...r, times });
              }}
            />
            <IconButton icon="trash" variant="danger" label={`Remove time ${i + 1}`} disabled={r.times.length === 1} onClick={() => setR({ ...r, times: r.times.filter((_, j) => j !== i) })} />
          </div>
        ))}
        <div>
          <Button size="sm" variant="quiet" icon="plus" onClick={() => setR({ ...r, times: [...r.times, '12:00'] })}>
            Add a time
          </Button>
        </div>
      </fieldset>

      <fieldset className="stack plain-fieldset" style={{ ['--gap' as string]: 'var(--s-3)' }}>
        <legend className="field-label">When</legend>
        <div className="segmented" role="radiogroup" aria-label="Repeat">
          <button type="button" role="radio" aria-checked={!once} className={!once ? 'is-on' : ''} onClick={() => setOnce(false)}>
            Repeats
          </button>
          <button type="button" role="radio" aria-checked={once} className={once ? 'is-on' : ''} onClick={() => setOnce(true)}>
            One day
          </button>
        </div>
        {once ? (
          <Field label="Day">{(ids) => <Input {...ids} type="date" value={r.date ?? ''} onChange={(e) => setR({ ...r, date: e.target.value || null })} />}</Field>
        ) : (
          <div>
            <div className="days" role="group" aria-label="Days of the week">
              {DAY_NAMES.map((name, i) => {
                const day = i + 1;
                const on = r.daysOfWeek.length === 0 || r.daysOfWeek.includes(day);
                return (
                  <button
                    key={name}
                    type="button"
                    aria-pressed={on}
                    className={['day', on ? 'is-on' : ''].join(' ')}
                    onClick={() => {
                      setR({ ...r, daysOfWeek: toggleDay(r.daysOfWeek, day) });
                    }}
                  >
                    {name}
                  </button>
                );
              })}
            </div>
            <p className="field-hint">{describeDays(r.daysOfWeek)}</p>
          </div>
        )}
      </fieldset>

      <Checkbox
        checked={r.notifyFamilyIfMissed}
        onChange={(v) => setR({ ...r, notifyFamilyIfMissed: v })}
        label="Tell me if it’s missed"
        description={`If ${parent} hasn’t tapped Done within an hour, you’ll get a gentle note. ${parent} can see this setting.`}
      />
      {error ? (
        <p className="field-error" role="alert">
          {error}
        </p>
      ) : null}
    </Dialog>
  );
}

export function RemindersTab({ device }: { device: Device }) {
  const { config, update, loading } = useConfigEditor();
  const [editing, setEditing] = useState<Reminder | null>(null);
  if (loading || !config) return <Spinner />;
  const blank = (): Reminder => ({ id: newId('r'), title: '', detail: '', kind: 'MEDICATION', times: ['08:00'], daysOfWeek: [], date: null, notifyFamilyIfMissed: false });
  const sorted = config.reminders.slice().sort((a, b) => (a.times[0] ?? '').localeCompare(b.times[0] ?? ''));

  return (
    <div className="stack" style={{ ['--gap' as string]: 'var(--s-5)' }}>
      <div className="row spread">
        <p className="lede grow">Pills and appointments, on a big card at the right time.</p>
        <Button variant="primary" icon="plus" onClick={() => setEditing(blank())}>
          New reminder
        </Button>
      </div>
      {sorted.length === 0 ? (
        <EmptyState icon="pill" title="No reminders yet">
          Medication times and appointments work well. Keep it to the ones that matter.
        </EmptyState>
      ) : (
        <ul className="list-plain stack" style={{ ['--gap' as string]: 'var(--s-3)' }}>
          {sorted.map((r) => (
            <li key={r.id}>
              <Card className="reminder">
                <span className={`reminder-icon kind-${r.kind.toLowerCase()}`} aria-hidden="true">
                  <Icon name={KIND_ICON[r.kind]} size={24} />
                </span>
                <div className="grow">
                  <h2 className="reminder-title">{r.title}</h2>
                  {r.detail ? <p className="muted">{r.detail}</p> : null}
                  <p className="reminder-when">
                    <Icon name="clock" size={16} /> {describeSchedule(r)}
                  </p>
                  {r.notifyFamilyIfMissed ? <Badge tone="brand">Tells you if missed</Badge> : null}
                </div>
                <div className="reminder-actions">
                  <IconButton icon="edit" label={`Edit ${r.title}`} onClick={() => setEditing(r)} />
                  <IconButton icon="trash" variant="danger" label={`Delete ${r.title}`} onClick={() => update((c) => void (c.reminders = c.reminders.filter((x) => x.id !== r.id)))} />
                </div>
              </Card>
            </li>
          ))}
        </ul>
      )}
      {editing ? (
        <ReminderDialog
          initial={editing}
          parent={device.parentName}
          onClose={() => setEditing(null)}
          onSave={(r) => {
            update((c) => {
              const i = c.reminders.findIndex((x) => x.id === r.id);
              if (i >= 0) c.reminders[i] = r;
              else c.reminders.push(r);
            });
            setEditing(null);
          }}
        />
      ) : null}
    </div>
  );
}
