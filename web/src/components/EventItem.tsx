import type { EventDoc } from '../lib/types';
import { describeEvent, SEVERITY_LABEL } from '../lib/eventText';
import { formatTimeOfDay, relativeTime } from '../lib/time';
import { useFileUrl } from '../data/hooks';
import { Icon } from './Icon';

/** One alert in a feed. URGENT is prominent but calm: warm amber, never red. */
export function EventItem({ event, parentName, now, compact = false, familyId, deviceId }: { event: EventDoc; parentName: string; now: number; compact?: boolean; familyId?: string; deviceId?: string }) {
  const text = describeEvent(event, parentName);
  const sev = event.severity.toLowerCase();
  const shotPath = event.type === 'HELP_REQUESTED' ? (event.data.storagePath ?? (event.data.screenshot && familyId && deviceId ? `families/${familyId}/devices/${deviceId}/help/${event.data.screenshot}.jpg` : undefined)) : undefined;
  const shot = useFileUrl(compact ? undefined : shotPath);
  return (
    <article className={`event event-${sev} ${compact ? 'event-compact' : ''}`} aria-label={`${SEVERITY_LABEL[event.severity]}: ${text.title}`}>
      <span className="event-icon" aria-hidden="true">
        <Icon name={text.icon === 'remote' ? 'remote' : text.icon === 'check' ? 'checkCircle' : text.icon} size={compact ? 20 : 22} />
      </span>
      <div className="event-main">
        <div className="event-top">
          <h3 className="event-title">
            {text.title}
            {!compact && event.severity === 'URGENT' ? <span className="badge badge-urgent event-sev">{SEVERITY_LABEL.URGENT}</span> : null}
          </h3>
          <time className="event-time" dateTime={new Date(event.at).toISOString()} title={new Date(event.at).toLocaleString()}>
            {compact ? relativeTime(event.at, now) : formatTimeOfDay(event.at)}
          </time>
        </div>
        <p className="event-body">{text.body}</p>
        {!compact && text.suggestion ? <p className="event-suggestion">{text.suggestion}</p> : null}
        {!compact && shot ? (
          <a href={shot} target="_blank" rel="noreferrer" className="event-shot">
            <img src={shot} alt={`Screenshot ${parentName} sent`} loading="lazy" />
          </a>
        ) : null}
      </div>
    </article>
  );
}
