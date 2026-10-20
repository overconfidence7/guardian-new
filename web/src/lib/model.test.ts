import { describe, expect, it } from 'vitest';
import fixture from '../../../shared/fixtures/device-config.example.json';
import {
  defaultDeviceConfig,
  parseDeviceConfig,
  primaryHelper,
  validateDeviceConfigStrict,
  type DeviceConfig,
} from './model';

describe('DeviceConfig contract (shared fixture)', () => {
  it('validates strictly: right types, valid enums, no unknown keys', () => {
    const result = validateDeviceConfigStrict(fixture);
    if (!result.success) throw new Error(JSON.stringify(result.error.issues, null, 2));
    expect(result.success).toBe(true);
  });

  it('decodes to the same values, adding only defaults the fixture leaves out', () => {
    const parsed = parseDeviceConfig(fixture);
    expect(parsed.parentName).toBe('Margaret');
    expect(parsed.revision).toBe(42);
    expect(parsed.home.tiles).toHaveLength(8);
    expect(parsed.home.tiles[0]).toEqual({ id: 't1', kind: 'CALL_CONTACT', target: 'fam_sarah', label: 'Sarah' });
    // Defaults filled in, as kotlinx.serialization would.
    expect(parsed.home.tiles[2]!.target).toBe('');
    expect(parsed.contacts[1]!.note).toBe('');
    expect(parsed.reminders[1]!.notifyFamilyIfMissed).toBe(false);
    expect(parsed.reminders[1]!.daysOfWeek).toEqual([]);
    expect(parsed.protection.pauseTripwires.extraPackages).toEqual({ 'org.example.creditunion': 'BANK' });
    expect(parsed.protection.callScreening.allowlist).toHaveLength(2);
    expect(parsed.display).toEqual({ fontScale: 1.4, highContrast: false, theme: 'LIGHT' });
    expect(parsed.checkIn).toEqual({ enabled: true, deadline: '11:00', timezone: 'America/Chicago' });
  });

  it('round-trips: a parsed config is itself strictly valid', () => {
    const parsed = parseDeviceConfig(fixture);
    expect(validateDeviceConfigStrict(JSON.parse(JSON.stringify(parsed))).success).toBe(true);
  });

  it('type-checks the fixture against the TS model', () => {
    // A compile-time check: the parsed fixture is a DeviceConfig.
    const typed: DeviceConfig = parseDeviceConfig(fixture);
    expect(typed.schemaVersion).toBe(1);
  });
});

describe('defaults mirror Model.kt', () => {
  it('matches DeviceConfig() in Kotlin', () => {
    expect(defaultDeviceConfig()).toEqual({
      schemaVersion: 1,
      revision: 0,
      parentName: '',
      family: [],
      contacts: [],
      home: { tiles: [], showPhotoCard: true, columns: 2 },
      display: { fontScale: 1.3, highContrast: false, theme: 'LIGHT' },
      allowedApps: [],
      protection: {
        pauseTripwires: { enabled: true, windowMinutes: 30, categories: ['PAYMENT', 'GIFT_CARD', 'CRYPTO', 'BANK'], extraPackages: {} },
        remoteAccessAlarm: { enabled: true, trustedPackages: [], warnOnControlCapableInstalls: true },
        callScreening: { mode: 'SILENCE_UNKNOWN', allowlist: [], allowRepeatCallers: true },
        longCallAlert: { enabled: true, minutes: 20 },
        safeWord: { enabled: true, word: '' },
      },
      checkIn: { enabled: true, deadline: '11:00', timezone: 'America/New_York' },
      reminders: [],
      lock: { level: 'SIMPLE', remoteUnlockUntil: 0 },
    });
  });

  it('decodes an empty document to the defaults', () => {
    expect(parseDeviceConfig({})).toEqual(defaultDeviceConfig());
    expect(parseDeviceConfig(null)).toEqual(defaultDeviceConfig());
  });

  it('returns fresh objects each time', () => {
    const a = defaultDeviceConfig();
    a.protection.pauseTripwires.categories.push('REMOTE_ACCESS');
    expect(defaultDeviceConfig().protection.pauseTripwires.categories).toHaveLength(4);
  });
});

describe('lenient decoding (ignoreUnknownKeys + coerceInputValues)', () => {
  it('ignores unknown keys, like updatedBy/updatedAt on the Firestore doc', () => {
    const parsed = parseDeviceConfig({ ...fixture, updatedBy: { uid: 'x' }, futureField: 1 });
    expect(parsed).not.toHaveProperty('updatedBy');
    expect(parsed).not.toHaveProperty('futureField');
  });

  it('falls back to defaults for unknown enum values and nulls', () => {
    const parsed = parseDeviceConfig({
      display: { theme: 'NEON', fontScale: null },
      protection: { callScreening: { mode: 'BLOCK_EVERYONE' } },
    });
    expect(parsed.display.theme).toBe('LIGHT');
    expect(parsed.display.fontScale).toBe(1.3);
    expect(parsed.protection.callScreening.mode).toBe('SILENCE_UNKNOWN');
  });

  it('drops malformed list items instead of failing the whole document', () => {
    const parsed = parseDeviceConfig({
      home: { tiles: [{ id: 'ok', kind: 'PHONE' }, { id: 'bad', kind: 'TELEPORT' }, 'nonsense'] },
      protection: { pauseTripwires: { categories: ['BANK', 'BANK', 'NOPE'], extraPackages: { a: 'CRYPTO', b: 'WHAT' } } },
    });
    expect(parsed.home.tiles.map((t) => t.id)).toEqual(['ok']);
    expect(parsed.protection.pauseTripwires.categories).toEqual(['BANK']);
    expect(parsed.protection.pauseTripwires.extraPackages).toEqual({ a: 'CRYPTO' });
  });

  it('strict validation rejects what the lenient decoder repairs', () => {
    expect(validateDeviceConfigStrict({ display: { theme: 'NEON' } }).success).toBe(false);
    expect(validateDeviceConfigStrict({ surprise: true }).success).toBe(false);
  });
});

describe('primaryHelper', () => {
  it('prefers the primary helper with a number, then anyone with a number', () => {
    const cfg = parseDeviceConfig(fixture);
    expect(primaryHelper(cfg)?.name).toBe('Sarah');
    cfg.family[0]!.phone = '';
    expect(primaryHelper(cfg)?.name).toBe('David');
    cfg.family[1]!.phone = ' ';
    expect(primaryHelper(cfg)).toBeUndefined();
  });
});
