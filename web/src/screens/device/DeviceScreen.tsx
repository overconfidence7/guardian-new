import { Navigate, useParams } from 'react-router';
import { usePaths } from '../../data/DataProvider';
import { useDevice, useNow } from '../../data/hooks';
import { ButtonA, ButtonLink } from '../../components/Button';
import { EmptyState, Spinner } from '../../components/EmptyState';
import { Icon } from '../../components/Icon';
import { Avatar } from '../../components/Avatar';
import { TabPanel, Tabs, type TabItem } from '../../components/Tabs';
import { deviceHealth } from '../../lib/status';
import { relativeTime } from '../../lib/time';
import { telHref, useParentPhone } from '../../lib/parentPhone';
import { ConfigEditorProvider, SaveBar } from './ConfigEditor';
import { AlertsTab } from './AlertsTab';
import { HomeScreenTab } from './HomeScreenTab';
import { PeopleTab } from './PeopleTab';
import { PhotosTab } from './PhotosTab';
import { ProtectionTab } from './ProtectionTab';
import { RemindersTab } from './RemindersTab';
import { PhoneTab } from './PhoneTab';
import { ChangesTab } from './ChangesTab';

const TABS = [
  { id: 'alerts', label: 'Alerts', icon: 'bell' },
  { id: 'home', label: 'Home screen', icon: 'grid' },
  { id: 'people', label: 'People', icon: 'users' },
  { id: 'photos', label: 'Photos', icon: 'image' },
  { id: 'protection', label: 'Protection', icon: 'shield' },
  { id: 'reminders', label: 'Reminders', icon: 'pill' },
  { id: 'phone', label: 'Phone', icon: 'smartphone' },
  { id: 'changes', label: 'Recent changes', icon: 'history' },
] as const satisfies readonly Omit<TabItem, 'to'>[];

export type DeviceTab = (typeof TABS)[number]['id'];

export function DeviceScreen() {
  const { familyId = '', deviceId = '', tab = 'alerts' } = useParams();
  const paths = usePaths();
  const device = useDevice(familyId, deviceId);
  const now = useNow(30_000);
  const parentPhone = useParentPhone(deviceId);

  if (!TABS.some((t) => t.id === tab)) return <Navigate to={paths.device(familyId, deviceId)} replace />;
  if (device.loading) return <Spinner />;
  if (!device.data) {
    return (
      <div className="page">
        <EmptyState icon="smartphone" title="This phone isn’t here anymore" action={<ButtonLink to={paths.family(familyId)}>Back to family</ButtonLink>}>
          It may have been removed by another helper.
        </EmptyState>
      </div>
    );
  }
  const d = device.data;
  const health = deviceHealth(d, now);
  const last = d.status?.lastHeartbeatAt ?? d.status?.lastSeenAt ?? null;
  const items: TabItem[] = TABS.map((t) => ({ ...t, to: paths.device(familyId, deviceId, t.id) }));

  return (
    <ConfigEditorProvider familyId={familyId} deviceId={deviceId} parentName={d.parentName}>
      <div className="page device-page">
        <ButtonLink to={paths.family(familyId)} variant="ghost" size="sm" icon="chevronLeft" className="back-link">
          Family home
        </ButtonLink>
        <header className="device-header">
          <Avatar name={d.parentName} size={64} />
          <div className="grow">
            <h1 className="h-page">{d.parentName}’s phone</h1>
            <p className="device-header-meta">
              <span className={`status-pill status-${health.kind}`}>
                <span className={`status-dot ${health.kind === 'protected' ? 'ok' : health.kind === 'attention' ? 'attention' : health.kind === 'quiet' ? 'quiet' : ''}`} aria-hidden="true" />
                {health.kind === 'attention' ? `Needs attention: ${health.detail.toLowerCase()}` : health.label}
              </span>
              {last ? <span className="subtle small">Active {relativeTime(last, now)}</span> : null}
            </p>
          </div>
          {parentPhone ? (
            <ButtonA href={telHref(parentPhone)} variant="primary" icon="phone" className="device-call">
              Call {d.parentName}
            </ButtonA>
          ) : null}
        </header>
        {health.kind === 'not-set-up' ? (
          <div className="notice notice-info setup-nudge">
            <Icon name="qr" size={22} />
            <div className="grow">
              <strong>Not connected yet.</strong> <span className="muted">You can set things up now. They’ll show up on the phone once it’s connected.</span>
            </div>
            <ButtonLink to={paths.setup(familyId, deviceId)} variant="quiet" size="sm">
              Setup code
            </ButtonLink>
          </div>
        ) : null}

        <Tabs items={items} current={tab} label={`${d.parentName}’s phone`} idBase="device-tab" />
        <TabPanel current={tab} idBase="device-tab">
          {tab === 'alerts' ? <AlertsTab familyId={familyId} device={d} /> : null}
          {tab === 'home' ? <HomeScreenTab familyId={familyId} device={d} /> : null}
          {tab === 'people' ? <PeopleTab familyId={familyId} device={d} /> : null}
          {tab === 'photos' ? <PhotosTab familyId={familyId} device={d} /> : null}
          {tab === 'protection' ? <ProtectionTab familyId={familyId} device={d} /> : null}
          {tab === 'reminders' ? <RemindersTab device={d} /> : null}
          {tab === 'phone' ? <PhoneTab familyId={familyId} device={d} /> : null}
          {tab === 'changes' ? <ChangesTab familyId={familyId} device={d} /> : null}
        </TabPanel>
      </div>
      <SaveBar />
    </ConfigEditorProvider>
  );
}
