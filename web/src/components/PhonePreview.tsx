/*
 * Approximate render of the parent's Guardian home screen (SPEC F1): clock, photo
 * card, big tiles, next reminder, shield chip and "All my apps". Used by the tile
 * editor, the display settings and the landing page.
 */
import type { CSSProperties } from 'react';
import type { DeviceConfig, HomeTile, InstalledApp, TileKind } from '../lib/model';
import { callablePeople, tileLabel } from '../lib/tiles';
import { formatHHmm } from '../lib/time';
import { Avatar } from './Avatar';
import { Icon, type IconName } from './Icon';
import { ShieldMark } from './Logo';

const KIND_ICON: Record<TileKind, IconName> = {
  CALL_CONTACT: 'phone',
  APP: 'apps',
  PHONE: 'phone',
  MESSAGES: 'message',
  CAMERA: 'camera',
  PHOTOS: 'image',
  REMINDERS: 'bell',
  HELP: 'hand',
  ALL_APPS: 'grid',
};

const KIND_TONE: Partial<Record<TileKind, string>> = {
  PHONE: 'tone-green',
  MESSAGES: 'tone-blue',
  CAMERA: 'tone-plum',
  PHOTOS: 'tone-amber',
  REMINDERS: 'tone-rose',
  HELP: 'tone-teal',
  ALL_APPS: 'tone-slate',
  APP: 'tone-slate',
};

export interface PreviewPhoto {
  url?: string;
  fromName: string;
  caption: string;
}

function nextReminder(config: DeviceConfig, now: Date) {
  const today = now.toISOString().slice(0, 10);
  const minutesNow = now.getHours() * 60 + now.getMinutes();
  const isoDay = ((now.getDay() + 6) % 7) + 1;
  let best: { title: string; time: string; at: number } | null = null;
  for (const r of config.reminders) {
    if (r.date && r.date !== today) continue;
    if (!r.date && r.daysOfWeek.length > 0 && !r.daysOfWeek.includes(isoDay)) continue;
    for (const t of r.times) {
      const [h, m] = t.split(':').map(Number);
      const at = (h ?? 0) * 60 + (m ?? 0);
      if (at >= minutesNow && (!best || at < best.at)) best = { title: r.title, time: t, at };
    }
  }
  return best;
}

export function PhonePreview({
  config,
  apps = [],
  photo,
  highlightTileId,
  now = new Date(),
  label = 'Preview of the home screen on the phone',
  compact = false,
}: {
  config: DeviceConfig;
  apps?: readonly InstalledApp[];
  photo?: PreviewPhoto | null;
  highlightTileId?: string | null;
  now?: Date;
  label?: string;
  compact?: boolean;
}) {
  const { display, home } = config;
  const dark = display.theme === 'DARK';
  const people = callablePeople(config);
  const reminder = nextReminder(config, now);
  const columns = Math.max(1, Math.min(3, home.columns));
  const style = {
    '--fs': String(display.fontScale),
    '--cols': String(columns),
  } as CSSProperties;

  const time = now.toLocaleTimeString('en-US', { hour: 'numeric', minute: '2-digit' }).replace(/\s?[AP]M$/, '');
  const date = now.toLocaleDateString('en-US', { weekday: 'long', month: 'long', day: 'numeric' });

  return (
    <figure className={['phone', compact ? 'phone-compact' : ''].join(' ')} aria-label={label}>
      <div className="phone-frame">
        <div className={['phone-screen', dark ? 'is-dark' : '', display.highContrast ? 'is-contrast' : ''].join(' ')} style={style}>
          <div className="ph-status" aria-hidden="true">
            <span>{time}</span>
            <span className="ph-status-icons">
              <span className="ph-signal" />
              <span className="ph-battery" />
            </span>
          </div>
          <div className="ph-scroll">
            <div className="ph-clock">
              <div className="ph-time">{time}</div>
              <div className="ph-date">{date}</div>
            </div>

            {home.showPhotoCard ? (
              <div className="ph-photo">
                {photo?.url ? <img src={photo.url} alt="" /> : <div className="ph-photo-empty"><Icon name="image" size={28} /></div>}
                <div className="ph-photo-caption">
                  <strong>Today from {photo?.fromName || 'your family'}</strong>
                  {photo?.caption ? <span>{photo.caption}</span> : null}
                </div>
              </div>
            ) : null}

            {reminder ? (
              <div className="ph-reminder">
                <Icon name="bell" size={16} />
                <span>
                  <strong>{formatHHmm(reminder.time)}</strong> {reminder.title}
                </span>
              </div>
            ) : null}

            <ul className="ph-tiles" aria-label="Tiles">
              {home.tiles.length === 0 ? <li className="ph-tiles-empty">No tiles yet</li> : null}
              {home.tiles.map((tile: HomeTile) => {
                const person = tile.kind === 'CALL_CONTACT' ? people.find((p) => p.id === tile.target) : undefined;
                const text = tileLabel(tile, config, apps);
                return (
                  <li key={tile.id} className={['ph-tile', person ? 'is-person' : (KIND_TONE[tile.kind] ?? ''), highlightTileId === tile.id ? 'is-highlight' : ''].join(' ')}>
                    {person ? (
                      <span className="ph-tile-photo">
                        <Avatar name={person.name} src={person.photoUrl} size={56} />
                      </span>
                    ) : tile.kind === 'APP' ? (
                      <span className="ph-tile-icon ph-app-icon" aria-hidden="true">
                        {text.slice(0, 1).toUpperCase()}
                      </span>
                    ) : (
                      <span className="ph-tile-icon">
                        <Icon name={KIND_ICON[tile.kind]} size={28} />
                      </span>
                    )}
                    <span className="ph-tile-label">{tile.kind === 'CALL_CONTACT' ? `Call ${text}` : text}</span>
                  </li>
                );
              })}
            </ul>

            <div className="ph-chip">
              <ShieldMark size={18} />
              <span>Protected · What my family sees</span>
            </div>
            <div className="ph-all-apps">All my apps</div>
          </div>
          <div className="ph-home-bar" aria-hidden="true" />
        </div>
      </div>
    </figure>
  );
}
