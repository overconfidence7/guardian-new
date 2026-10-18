import { useState } from 'react';
import { useEvents, useNow } from '../../data/hooks';
import { Button } from '../../components/Button';
import { EmptyState, Spinner } from '../../components/EmptyState';
import { EventItem } from '../../components/EventItem';
import { Field, Input } from '../../components/Field';
import { Icon } from '../../components/Icon';
import { setParentPhone, useParentPhone } from '../../lib/parentPhone';
import { dayLabel } from '../../lib/time';
import type { Device, EventDoc } from '../../lib/types';

function groupByDay(events: EventDoc[], now: number) {
  const groups: { label: string; items: EventDoc[] }[] = [];
  for (const e of events) {
    const label = dayLabel(e.at, now);
    const g = groups[groups.length - 1];
    if (g && g.label === label) g.items.push(e);
    else groups.push({ label, items: [e] });
  }
  return groups;
}

function CallCard({ device }: { device: Device }) {
  const phone = useParentPhone(device.id);
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(phone);
  if (phone && !editing) {
    // The header already has the big "Call" button; here we only show which number it uses.
    return (
      <p className="small muted call-number">
        The Call button dials {phone}. Calls from family always ring through, even with screening on.{' '}
        <button type="button" className="link-btn small" onClick={() => { setValue(phone); setEditing(true); }}>
          Change number
        </button>
      </p>
    );
  }
  return (
    <form
      className="call-card"
      onSubmit={(e) => {
        e.preventDefault();
        setParentPhone(device.id, value);
        setEditing(false);
      }}
    >
      <Field label={`${device.parentName}’s phone number`} hint="Saved only on this device, for one-tap calling." className="grow">
        {(ids) => <Input {...ids} type="tel" inputMode="tel" autoComplete="off" placeholder="+1 555 010 0000" value={value} onChange={(e) => setValue(e.target.value)} />}
      </Field>
      <Button type="submit" variant="secondary" icon="check" disabled={!value.trim() && !phone}>
        Save number
      </Button>
    </form>
  );
}

export function AlertsTab({ familyId, device }: { familyId: string; device: Device }) {
  const [max, setMax] = useState(40);
  const events = useEvents(familyId, device.id, max);
  const now = useNow(30_000);
  const list = (events.data ?? []).slice().sort((a, b) => (b.createdAt ?? b.at) - (a.createdAt ?? a.at));

  return (
    <div className="stack" style={{ ['--gap' as string]: 'var(--s-6)' }}>
      <CallCard device={device} />
      {events.loading ? (
        <Spinner label="Loading alerts" />
      ) : list.length === 0 ? (
        <EmptyState icon="sun" title="All quiet">
          When something needs you, it will show up here and on your phone. Most days, nothing will.
        </EmptyState>
      ) : (
        <div className="feed" aria-live="polite" aria-relevant="additions">
          {groupByDay(list, now).map((g) => (
            <section key={g.label} className="feed-day" aria-label={g.label}>
              <h2 className="feed-day-label">{g.label}</h2>
              <ul className="list-plain stack" style={{ ['--gap' as string]: 'var(--s-3)' }}>
                {g.items.map((e) => (
                  <li key={e.id}>
                    <EventItem event={e} parentName={device.parentName} now={now} familyId={familyId} deviceId={device.id} />
                  </li>
                ))}
              </ul>
            </section>
          ))}
          {events.data && events.data.length >= max ? (
            <Button variant="ghost" onClick={() => setMax((m) => m + 40)}>
              Show older
            </Button>
          ) : (
            <p className="small subtle center-text">
              <Icon name="lock" size={14} className="inline-icon" /> Alerts are kept for 90 days. {device.parentName} can see every alert you see.
            </p>
          )}
        </div>
      )}
    </div>
  );
}
