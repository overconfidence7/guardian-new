/* Fixture state for /demo. Built from the shared contract fixture so the demo always matches the phone. */
import fixture from '../../../shared/fixtures/device-config.example.json';
import { parseDeviceConfig, type DeviceConfig } from '../lib/model';
import type { ChangeDoc, ConfigDoc, Device, EventDoc, Family, Member, PhotoDoc } from '../lib/types';
import { DEMO_PHOTOS } from './demoArt';

export const DEMO_USER = {
  uid: 'demo-sarah',
  displayName: 'Sarah',
  email: 'sarah@example.com',
  photoUrl: null,
};

export const DEMO_FAMILY_ID = 'demo-family';
export const DEMO_MOM = 'mom-phone';
export const DEMO_DAD = 'dad-phone';

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

export interface DemoState {
  families: Record<string, Family>;
  members: Record<string, Member[]>;
  devices: Record<string, Device[]>;
  configs: Record<string, ConfigDoc>;
  events: Record<string, EventDoc[]>;
  photos: Record<string, PhotoDoc[]>;
  changes: Record<string, ChangeDoc[]>;
}

const APPS = [
  ['com.whatsapp', 'WhatsApp'],
  ['com.google.android.youtube', 'YouTube'],
  ['com.facebook.katana', 'Facebook'],
  ['com.chase.sig.android', 'Chase'],
  ['com.zellepay.zelle', 'Zelle'],
  ['com.amazon.mShop.android.shopping', 'Amazon Shopping'],
  ['org.example.creditunion', 'First Community CU'],
  ['com.google.android.apps.maps', 'Maps'],
  ['com.google.android.gm', 'Gmail'],
  ['com.android.chrome', 'Chrome'],
  ['com.weather.Weather', 'The Weather Channel'],
  ['com.google.android.deskclock', 'Clock'],
  ['com.google.android.calculator', 'Calculator'],
  ['com.audible.application', 'Audible'],
  ['com.nytimes.crosswords', 'NYT Games'],
  ['com.google.android.apps.chromecast.app', 'Google Home'],
].map(([packageName, label]) => ({ packageName: packageName!, label: label! }));

function ev(id: string, type: EventDoc['type'], severity: EventDoc['severity'], at: number, data: Record<string, string> = {}): EventDoc {
  return { id, type, severity, at, data, createdAt: at + 1500, source: 'device', deliveredTo: ['demo-sarah', 'demo-david'] };
}

export function buildDemoState(now: number = Date.now()): DemoState {
  const momConfig: DeviceConfig = parseDeviceConfig(fixture);
  momConfig.family = momConfig.family.map((f) => ({ ...f }));

  const dadConfig: DeviceConfig = parseDeviceConfig({
    ...fixture,
    parentName: 'Robert',
    revision: 7,
    home: {
      ...fixture.home,
      tiles: fixture.home.tiles.filter((t) => t.kind !== 'APP'),
    },
    display: { fontScale: 1.6, highContrast: true, theme: 'LIGHT' },
    protection: {
      ...fixture.protection,
      callScreening: { ...fixture.protection.callScreening, mode: 'OFF' },
    },
    reminders: [],
  });

  const devices: Device[] = [
    {
      id: DEMO_MOM,
      parentName: 'Mom',
      deviceUid: 'anon-mom',
      model: 'Samsung Galaxy A15',
      androidVersion: '14',
      appVersion: '1.0.0',
      status: { protected: true, missing: [], lastHeartbeatAt: now - 4 * MIN, lastSeenAt: now - 3 * MIN },
      installedApps: APPS,
      checkIn: { enabled: true, deadline: '11:00', timezone: 'America/Chicago' },
      consent: { version: 1, acceptedAt: now - 30 * DAY, features: { home: true, pause: true, screening: true, checkIn: true, reminders: true } },
      createdAt: now - 31 * DAY,
    },
    {
      id: DEMO_DAD,
      parentName: 'Dad',
      deviceUid: 'anon-dad',
      model: 'Moto G Play',
      androidVersion: '13',
      appVersion: '1.0.0',
      status: { protected: false, missing: ['callScreening', 'battery'], lastHeartbeatAt: now - 2 * HOUR - 12 * MIN, lastSeenAt: now - 2 * HOUR },
      installedApps: APPS.filter((a) => !['com.zellepay.zelle', 'org.example.creditunion'].includes(a.packageName)),
      checkIn: { enabled: true, deadline: '11:00', timezone: 'America/Chicago' },
      consent: { version: 1, acceptedAt: now - 12 * DAY, features: { home: true, pause: true, screening: false, checkIn: true } },
      createdAt: now - 12 * DAY,
    },
  ];

  const momEvents: EventDoc[] = [
    ev('e1', 'PAUSE_SHOWN', 'NOTICE', now - 4 * MIN, { app: 'Chase', category: 'BANK', duringCall: 'false', minutesSinceCall: '4' }),
    ev('e2', 'HELP_REQUESTED', 'NOTICE', now - 3 * HOUR - 20 * MIN, { note: 'Is this real?' }),
    ev('e3', 'REMINDER_MISSED', 'NOTICE', now - DAY - 2 * HOUR, { title: 'Blood pressure pill', time: '20:00' }),
    ev('e4', 'LONG_UNKNOWN_CALL', 'URGENT', now - 2 * DAY - 3 * HOUR, { minutes: '20', ongoing: 'true' }),
    ev('e5', 'PAUSE_CALLED_FAMILY', 'NOTICE', now - 2 * DAY - 3 * HOUR - 9 * MIN, { app: 'Zelle' }),
    ev('e6', 'PAUSE_SHOWN', 'URGENT', now - 2 * DAY - 3 * HOUR - 10 * MIN, { app: 'Zelle', category: 'PAYMENT', duringCall: 'true', minutesSinceCall: '0' }),
    ev('e7', 'REMOTE_ACCESS_INSTALLED', 'URGENT', now - 2 * DAY - 3 * HOUR - 17 * MIN, { app: 'AnyDesk', afterUnknownCall: 'true' }),
    ev('e8', 'PROTECTION_CHANGED_BY_PARENT', 'NOTICE', now - 4 * DAY, { feature: 'longCall', enabled: 'false' }),
    ev('e9', 'CHECK_IN_MISSED', 'NOTICE', now - 5 * DAY, { deadline: '11:00' }),
  ];
  momEvents[7]!.source = 'server';

  const dadEvents: EventDoc[] = [
    ev('d1', 'PERMISSION_MISSING', 'NOTICE', now - 2 * HOUR - 30 * MIN, { permission: 'callScreening' }),
    ev('d2', 'CONTROL_APP_INSTALLED', 'NOTICE', now - 3 * DAY, { app: 'Super Cleaner Pro', afterUnknownCall: 'false' }),
  ];

  const momPhotos: PhotoDoc[] = [
    { id: 'p1', storagePath: DEMO_PHOTOS.school, fromUid: 'demo-sarah', fromName: 'Sarah', caption: 'Emma’s first day of second grade!', createdAt: now - 5 * HOUR },
    { id: 'p2', storagePath: DEMO_PHOTOS.dog, fromUid: 'demo-david', fromName: 'David', caption: 'Biscuit says hi', createdAt: now - 2 * DAY },
    { id: 'p3', storagePath: DEMO_PHOTOS.sunset, fromUid: 'demo-sarah', fromName: 'Sarah', caption: 'Sunset at the lake', createdAt: now - 6 * DAY },
    { id: 'p4', storagePath: DEMO_PHOTOS.garden, fromUid: 'demo-david', fromName: 'David', caption: 'Your roses are blooming', createdAt: now - 9 * DAY },
  ];

  const momChanges: ChangeDoc[] = [
    { id: 'c1', byUid: 'demo-sarah', byName: 'Sarah', summary: 'Sarah changed text size to 140%', at: now - 26 * HOUR },
    { id: 'c2', byUid: 'demo-david', byName: 'David', summary: 'David added the reminder “Dr. Patel checkup”', at: now - 3 * DAY },
    { id: 'c3', byUid: 'demo-sarah', byName: 'Sarah', summary: 'Sarah added CVS Pharmacy to numbers that always ring', at: now - 8 * DAY },
    { id: 'c4', byUid: 'demo-sarah', byName: 'Sarah', summary: 'Sarah added a WhatsApp tile to the home screen', at: now - 14 * DAY },
  ];

  const key = (d: string) => `${DEMO_FAMILY_ID}/${d}`;
  return {
    families: {
      [DEMO_FAMILY_ID]: {
        id: DEMO_FAMILY_ID,
        name: 'The Rivera family',
        plan: 'family',
        ownerUid: 'demo-sarah',
        memberUids: ['demo-sarah', 'demo-david'],
        createdAt: now - 31 * DAY,
      },
    },
    members: {
      [DEMO_FAMILY_ID]: [
        { uid: 'demo-sarah', role: 'owner', displayName: 'Sarah', email: 'sarah@example.com', photoUrl: null, phone: '+1 555 010 1111', pushTokens: ['demo-token'], notify: { urgent: 'push', notice: 'push' }, status: 'active', joinedAt: now - 31 * DAY },
        { uid: 'demo-david', role: 'helper', displayName: 'David', email: 'david@example.com', photoUrl: null, phone: '+1 555 010 2222', pushTokens: [], notify: { urgent: 'push', notice: 'off' }, status: 'active', joinedAt: now - 20 * DAY },
        { uid: 'demo-aunt', role: 'helper', displayName: 'Aunt Linda', email: 'linda@example.com', photoUrl: null, phone: '+1 555 010 7777', pushTokens: [], notify: { urgent: 'push', notice: 'push' }, status: 'pending', joinedAt: now - 2 * HOUR },
      ],
    },
    devices: { [DEMO_FAMILY_ID]: devices },
    configs: {
      [key(DEMO_MOM)]: { config: momConfig, updatedBy: { uid: 'demo-sarah', name: 'Sarah', kind: 'helper' }, updatedAt: now - 26 * HOUR, exists: true },
      [key(DEMO_DAD)]: { config: dadConfig, updatedBy: { uid: 'demo-david', name: 'David', kind: 'helper' }, updatedAt: now - 5 * DAY, exists: true },
    },
    events: { [key(DEMO_MOM)]: momEvents, [key(DEMO_DAD)]: dadEvents },
    photos: { [key(DEMO_MOM)]: momPhotos, [key(DEMO_DAD)]: [] },
    changes: { [key(DEMO_MOM)]: momChanges, [key(DEMO_DAD)]: [] },
  };
}
