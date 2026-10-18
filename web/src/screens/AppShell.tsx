import { useEffect, useRef, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate, useParams } from 'react-router';
import { useData, usePaths, useUser } from '../data/DataProvider';
import { useMyFamilies } from '../data/hooks';
import { Avatar } from '../components/Avatar';
import { Icon } from '../components/Icon';
import { ShieldMark } from '../components/Logo';

function UserMenu() {
  const user = useUser();
  const { api, base } = useData();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onDoc = (e: MouseEvent) => {
      if (!ref.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && setOpen(false);
    document.addEventListener('mousedown', onDoc);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onDoc);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  return (
    <div className="menu" ref={ref}>
      <button type="button" className="menu-trigger" aria-expanded={open} aria-haspopup="true" onClick={() => setOpen((o) => !o)}>
        <Avatar name={user.displayName} src={user.photoUrl} size={36} />
        <span className="sr-only">Account menu for {user.displayName}</span>
      </button>
      {open ? (
        <div className="menu-popover" role="menu">
          <div className="menu-head">
            <strong>{user.displayName}</strong>
            <span className="small subtle">{user.email}</span>
          </div>
          <button
            type="button"
            role="menuitem"
            className="menu-item"
            onClick={async () => {
              setOpen(false);
              await api.auth.signOut();
              navigate(base || '/');
            }}
          >
            <Icon name="logout" size={20} /> Sign out
          </button>
        </div>
      ) : null}
    </div>
  );
}

export function AppShell() {
  const { familyId } = useParams();
  const { api, base } = useData();
  const paths = usePaths();
  const families = useMyFamilies();
  const location = useLocation();
  const current = families.data?.find((f) => f.id === familyId) ?? families.data?.[0];

  // Remember the last family so "/" opens it next time.
  useEffect(() => {
    if (!familyId) return;
    try {
      localStorage.setItem('gm.lastFamily', familyId);
    } catch {
      /* ignore */
    }
  }, [familyId]);

  // Move focus to the main region on navigation, for screen readers and keyboards.
  const mainRef = useRef<HTMLElement>(null);
  const first = useRef(true);
  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    mainRef.current?.focus({ preventScroll: true });
    window.scrollTo({ top: 0 });
  }, [location.pathname.split('/').slice(0, 6).join('/')]); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <>
      <a href="#main" className="skip-link">
        Skip to content
      </a>
      {api.mode === 'demo' ? (
        <div className="demo-banner" role="note">
          <span>
            <strong>Demo</strong> · sample family, nothing is saved
          </span>
          <Link to="/">Start for real</Link>
        </div>
      ) : null}
      <header className="topbar">
        <div className="topbar-inner">
          <Link to={current ? paths.family(current.id) : paths.home()} className="brand-link" aria-label="Guardian Mode home">
            <ShieldMark size={30} />
            <span className="brand-name">{current?.name ?? 'Guardian Mode'}</span>
          </Link>
          {current ? (
            <nav className="topnav" aria-label="Main">
              <NavLink to={paths.family(current.id)} end className="topnav-link">
                <Icon name="home" size={20} />
                <span>Home</span>
              </NavLink>
              <NavLink to={paths.familySettings(current.id)} className="topnav-link">
                <Icon name="users" size={20} />
                <span>Family</span>
              </NavLink>
            </nav>
          ) : null}
          <UserMenu />
        </div>
        {families.data && families.data.length > 1 ? (
          <div className="family-switch">
            <label className="sr-only" htmlFor="family-switch">
              Family
            </label>
            <select
              id="family-switch"
              value={current?.id}
              onChange={(e) => {
                window.location.assign(`${base}/f/${e.target.value}`);
              }}
            >
              {families.data.map((f) => (
                <option key={f.id} value={f.id}>
                  {f.name}
                </option>
              ))}
            </select>
          </div>
        ) : null}
      </header>
      <main id="main" ref={mainRef} tabIndex={-1} className="main">
        <Outlet />
      </main>
    </>
  );
}
