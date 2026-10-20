import { serverTimestamp } from 'firebase/firestore';
import { describe, expect, it } from 'vitest';
import fixture from '../../../shared/fixtures/device-config.example.json';
import { changedPaths, deepEqual, stripUndefined, threeWayMerge } from './merge';
import { parseDeviceConfig } from './model';

describe('threeWayMerge', () => {
  const base = parseDeviceConfig(fixture);

  it('keeps a concurrent change to a different setting', () => {
    const local = structuredClone(base);
    local.display.fontScale = 1.8;
    const remote = structuredClone(base);
    remote.protection.callScreening.mode = 'OFF'; // e.g. the parent switched screening off
    const merged = threeWayMerge(base, local, remote);
    expect(merged.display.fontScale).toBe(1.8);
    expect(merged.protection.callScreening.mode).toBe('OFF');
  });

  it('local wins when both changed the same leaf', () => {
    const local = structuredClone(base);
    local.home.columns = 3;
    const remote = structuredClone(base);
    remote.home.columns = 1;
    expect(threeWayMerge(base, local, remote).home.columns).toBe(3);
  });

  it('treats arrays as whole values', () => {
    const local = structuredClone(base);
    local.home.tiles = local.home.tiles.slice(0, 2);
    const remote = structuredClone(base);
    remote.reminders = [];
    const merged = threeWayMerge(base, local, remote);
    expect(merged.home.tiles).toHaveLength(2);
    expect(merged.reminders).toEqual([]);
  });

  it('handles keys added and removed in maps', () => {
    const local = structuredClone(base);
    delete local.protection.pauseTripwires.extraPackages['org.example.creditunion'];
    const remote = structuredClone(base);
    remote.display.highContrast = true;
    const merged = threeWayMerge(base, local, remote);
    expect(merged.protection.pauseTripwires.extraPackages).toEqual({});
    expect(merged.display.highContrast).toBe(true);
  });

  it('returns remote when nothing changed locally', () => {
    const remote = structuredClone(base);
    remote.parentName = 'Maggie';
    expect(threeWayMerge(base, structuredClone(base), remote)).toBe(remote);
  });
});

describe('helpers', () => {
  it('deepEqual ignores undefined keys', () => {
    expect(deepEqual({ a: 1, b: undefined }, { a: 1 })).toBe(true);
    expect(deepEqual([1, { x: 2 }], [1, { x: 3 }])).toBe(false);
  });
  it('changedPaths', () => {
    expect(changedPaths({ a: { b: 1, c: 2 }, d: [1] }, { a: { b: 1, c: 3 }, d: [2] })).toEqual(['a.c', 'd']);
  });
  it('stripUndefined', () => {
    expect(stripUndefined({ a: undefined, b: [{ c: undefined, d: 1 }], e: null })).toEqual({ b: [{ d: 1 }], e: null });
  });
  it('stripUndefined leaves Firestore field values such as serverTimestamp() alone', () => {
    const stamp = serverTimestamp();
    const out = stripUndefined({ updatedAt: stamp, nested: { at: stamp, gone: undefined } });
    expect(out.updatedAt).toBe(stamp);
    expect(out.nested).toEqual({ at: stamp });
    expect(out.nested.at).toBe(stamp);
  });
});
