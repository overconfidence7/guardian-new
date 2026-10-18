import { useChanges, useNow } from '../../data/hooks';
import { Avatar } from '../../components/Avatar';
import { EmptyState, Spinner } from '../../components/EmptyState';
import { relativeTime } from '../../lib/time';
import type { Device } from '../../lib/types';

export function ChangesTab({ familyId, device }: { familyId: string; device: Device }) {
  const changes = useChanges(familyId, device.id, 50);
  const now = useNow(60_000);
  if (changes.loading) return <Spinner />;
  const list = changes.data ?? [];
  return (
    <div className="stack" style={{ ['--gap' as string]: 'var(--s-5)' }}>
      <p className="lede">Every change a helper makes is listed here, and on {device.parentName}’s phone under “What my family sees”.</p>
      {list.length === 0 ? (
        <EmptyState icon="history" title="No changes yet">
          When someone in the family changes a setting, it shows up here with their name.
        </EmptyState>
      ) : (
        <ol className="timeline">
          {list.map((c) => (
            <li key={c.id} className="timeline-item">
              <Avatar name={c.byName || '?'} size={36} />
              <div className="grow">
                <p className="timeline-text">{c.summary}</p>
                <p className="small subtle">{relativeTime(c.at, now)}</p>
              </div>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
