import { useRef, type KeyboardEvent, type ReactNode } from 'react';
import { Link, useNavigate } from 'react-router';
import { Icon, type IconName } from './Icon';

export interface TabItem {
  id: string;
  label: string;
  to: string;
  icon?: IconName;
  badge?: number;
}

/**
 * Route-backed tabs (WAI-ARIA tabs pattern with automatic activation). Arrow keys,
 * Home and End move between tabs; each tab is a real link so it can be opened or shared.
 */
export function Tabs({ items, current, label, idBase = 'tab' }: { items: TabItem[]; current: string; label: string; idBase?: string }) {
  const navigate = useNavigate();
  const refs = useRef<(HTMLAnchorElement | null)[]>([]);

  function onKeyDown(e: KeyboardEvent<HTMLDivElement>) {
    const index = items.findIndex((t) => t.id === current);
    let next = -1;
    if (e.key === 'ArrowRight') next = (index + 1) % items.length;
    else if (e.key === 'ArrowLeft') next = (index - 1 + items.length) % items.length;
    else if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = items.length - 1;
    if (next < 0) return;
    e.preventDefault();
    const item = items[next]!;
    navigate(item.to, { replace: true });
    refs.current[next]?.focus();
  }

  return (
    <div className="tabs" role="tablist" aria-label={label} onKeyDown={onKeyDown}>
      {items.map((t, i) => {
        const selected = t.id === current;
        return (
          <Link
            key={t.id}
            ref={(el) => {
              refs.current[i] = el;
            }}
            to={t.to}
            replace
            role="tab"
            id={`${idBase}-${t.id}`}
            aria-selected={selected}
            aria-controls={`${idBase}-panel`}
            tabIndex={selected ? 0 : -1}
            className={['tab', selected ? 'is-selected' : ''].join(' ')}
          >
            {t.icon ? <Icon name={t.icon} size={19} /> : null}
            <span>{t.label}</span>
            {t.badge ? (
              <span className="tab-badge" aria-label={`${t.badge} new`}>
                {t.badge}
              </span>
            ) : null}
          </Link>
        );
      })}
    </div>
  );
}

export function TabPanel({ current, idBase = 'tab', children }: { current: string; idBase?: string; children: ReactNode }) {
  return (
    <div role="tabpanel" id={`${idBase}-panel`} aria-labelledby={`${idBase}-${current}`} className="tab-panel" tabIndex={-1}>
      {children}
    </div>
  );
}
