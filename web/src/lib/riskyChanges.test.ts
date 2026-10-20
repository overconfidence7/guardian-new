import { describe, expect, it } from 'vitest';
import { defaultDeviceConfig, type DeviceConfig } from './model';
import { describeRequest, splitRiskyChanges } from './riskyChanges';

function config(): DeviceConfig {
  const c = defaultDeviceConfig({ parentName: 'Mom' });
  c.family = [
    { id: 'fam_sarah', name: 'Sarah', relation: 'Daughter', phone: '+1 555 010 1111', photoUrl: null, isPrimaryHelper: true },
    { id: 'fam_david', name: 'David', relation: 'Son', phone: '+1 555 010 2222', photoUrl: null, isPrimaryHelper: false },
  ];
  c.protection.remoteAccessAlarm.trustedPackages = ['com.teamviewer.host'];
  return c;
}

describe('splitRiskyChanges (docs/SECURITY.md §3)', () => {
  it('everyday edits apply at once', () => {
    const now = config();
    const wanted = structuredClone(now);
    wanted.display.fontScale = 1.6;
    wanted.home.columns = 3;
    wanted.family[1]!.name = 'Dave';
    wanted.protection.remoteAccessAlarm.trustedPackages = [];
    const { direct, request } = splitRiskyChanges(now, wanted);
    expect(request).toBeNull();
    expect(direct).toEqual(wanted);
  });

  it('holds every risky change and keeps the rest of the edit', () => {
    const now = config();
    const wanted = structuredClone(now);
    wanted.display.fontScale = 1.6;
    wanted.family[0]!.phone = '+1 555 010 9999';
    wanted.protection.remoteAccessAlarm.trustedPackages = ['com.teamviewer.host', 'com.anydesk.anydeskandroid'];
    wanted.protection.pauseTripwires.enabled = false;
    wanted.protection.remoteAccessAlarm.enabled = false;
    wanted.protection.longCallAlert.enabled = false;
    wanted.lock.remoteUnlockUntil = 1_800_000_000_000;
    const { direct, request } = splitRiskyChanges(now, wanted);
    expect(request).toEqual({
      family: wanted.family,
      trustedPackages: ['com.teamviewer.host', 'com.anydesk.anydeskandroid'],
      pauseTripwiresEnabled: false,
      remoteAccessAlarmEnabled: false,
      longCallAlertEnabled: false,
      remoteUnlockUntil: 1_800_000_000_000,
    });
    // What's written directly is exactly what the rules accept: the risky parts as they were.
    expect(direct.display.fontScale).toBe(1.6);
    expect(direct.family).toEqual(now.family);
    expect(direct.protection).toEqual(now.protection);
    expect(direct.lock).toEqual(now.lock);
  });

  it('a new primary helper, or a new person on the family list, waits too', () => {
    const now = config();
    const primary = structuredClone(now);
    primary.family = primary.family.map((f) => ({ ...f, isPrimaryHelper: f.name === 'David' }));
    expect(splitRiskyChanges(now, primary).request).toEqual({ family: primary.family });
    const added = structuredClone(now);
    added.family.push({ id: 'fam_x', name: 'Bank', relation: '', phone: '+1 555 010 0000', photoUrl: null, isPrimaryHelper: false });
    expect(splitRiskyChanges(now, added).request?.family).toHaveLength(3);
  });

  it('turning protection back on is never held', () => {
    const now = config();
    now.protection.longCallAlert.enabled = false;
    const wanted = structuredClone(now);
    wanted.protection.longCallAlert.enabled = true;
    expect(splitRiskyChanges(now, wanted)).toEqual({ direct: wanted, request: null });
  });

  it('describes what the parent will be asked', () => {
    expect(describeRequest({ longCallAlertEnabled: false })).toBe('turning off long-call notices');
    expect(describeRequest({ trustedPackages: [], family: [] })).toBe('the family’s phone numbers and trusting a remote-control app');
  });
});
