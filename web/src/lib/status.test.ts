import { describe, expect, it } from 'vitest';
import { deviceHealth } from './status';
import type { Device } from './types';

const base: Device = {
  id: 'd',
  parentName: 'Mom',
  deviceUid: 'anon',
  model: '',
  androidVersion: '',
  appVersion: '',
  status: { protected: true, missing: [], lastHeartbeatAt: 1_000_000, lastSeenAt: 1_000_000 },
  installedApps: [],
  checkIn: null,
  consent: null,
  createdAt: 0,
};

describe('deviceHealth', () => {
  it('not set up until a phone claims the code', () => {
    expect(deviceHealth({ ...base, deviceUid: null }).kind).toBe('not-set-up');
  });
  it('protected when nothing is missing', () => {
    expect(deviceHealth(base, 1_000_000 + 60_000).label).toBe('Protected');
  });
  it('lists missing settings in plain words', () => {
    const h = deviceHealth({ ...base, status: { ...base.status!, protected: false, missing: ['callScreening', 'battery', 'somethingNew'] } });
    expect(h.kind).toBe('attention');
    expect(h.detail).toContain('Screening turned off');
    expect(h.detail).toContain('Battery saver may pause Guardian');
    expect(h.detail).toContain('Something new needs attention');
  });
  it('quiet after a day without heartbeats', () => {
    expect(deviceHealth(base, 1_000_000 + 25 * 3600_000).kind).toBe('quiet');
  });
});
