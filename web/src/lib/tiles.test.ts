import { describe, expect, it } from 'vitest';
import fixture from '../../../shared/fixtures/device-config.example.json';
import { parseDeviceConfig } from './model';
import { callablePeople, dropIndex, makeTile, moveById, moveItem, removeById, starterTiles, tileLabel, tileProblem } from './tiles';

describe('moveItem', () => {
  const list = ['a', 'b', 'c', 'd'];
  it('moves down and up', () => {
    expect(moveItem(list, 0, 2)).toEqual(['b', 'c', 'a', 'd']);
    expect(moveItem(list, 3, 0)).toEqual(['d', 'a', 'b', 'c']);
  });
  it('clamps targets and ignores bad sources', () => {
    expect(moveItem(list, 1, 99)).toEqual(['a', 'c', 'd', 'b']);
    expect(moveItem(list, 1, -5)).toEqual(['b', 'a', 'c', 'd']);
    expect(moveItem(list, 9, 0)).toEqual(list);
  });
  it('does not mutate the input', () => {
    const copy = list.slice();
    moveItem(list, 0, 3);
    expect(list).toEqual(copy);
  });
});

describe('moveById / removeById', () => {
  const tiles = [{ id: 'x' }, { id: 'y' }, { id: 'z' }];
  it('moves by delta, clamped', () => {
    expect(moveById(tiles, 'y', -1).map((t) => t.id)).toEqual(['y', 'x', 'z']);
    expect(moveById(tiles, 'z', 1).map((t) => t.id)).toEqual(['x', 'y', 'z']);
    expect(moveById(tiles, 'missing', 1)).toEqual(tiles);
  });
  it('removes', () => {
    expect(removeById(tiles, 'x').map((t) => t.id)).toEqual(['y', 'z']);
  });
});

describe('dropIndex (drag)', () => {
  const mids = [25, 75, 125, 175];
  it('finds where a dragged item lands', () => {
    expect(dropIndex(mids, 25, 0)).toBe(0);
    expect(dropIndex(mids, 80, 0)).toBe(1);
    expect(dropIndex(mids, 200, 0)).toBe(3);
    expect(dropIndex(mids, 10, 3)).toBe(0);
    expect(dropIndex(mids, 130, 3)).toBe(3);
    expect(dropIndex(mids, 100, 3)).toBe(2);
  });
});

describe('tile helpers', () => {
  const config = parseDeviceConfig(fixture);
  it('labels tiles, falling back to targets', () => {
    expect(tileLabel({ id: '1', kind: 'CALL_CONTACT', target: 'c_patel', label: '' }, config)).toBe('Dr. Patel');
    expect(tileLabel({ id: '2', kind: 'APP', target: 'com.whatsapp', label: '' }, config, [{ packageName: 'com.whatsapp', label: 'WhatsApp' }])).toBe('WhatsApp');
    expect(tileLabel({ id: '3', kind: 'CAMERA', target: '', label: '' }, config)).toBe('Camera');
  });
  it('flags broken tiles', () => {
    expect(tileProblem({ id: '1', kind: 'CALL_CONTACT', target: 'gone', label: '' }, config, [])).toMatch(/removed/);
    expect(tileProblem({ id: '2', kind: 'APP', target: 'com.x', label: 'X' }, config, [{ packageName: 'com.y', label: 'Y' }])).toMatch(/isn’t on the phone/);
    expect(tileProblem(config.home.tiles[0]!, config, [])).toBeNull();
  });
  it('makes tiles with unique ids and sensible labels', () => {
    const a = makeTile('PHONE');
    const b = makeTile('PHONE');
    expect(a.id).not.toBe(b.id);
    expect(a.label).toBe('Phone');
    expect(makeTile('CALL_CONTACT').label).toBe('');
  });
  it('lists callable people, family first', () => {
    expect(callablePeople(config).map((p) => p.id)).toEqual(['fam_sarah', 'fam_david', 'c_patel', 'c_june']);
  });
  it('starter layout puts family first', () => {
    expect(starterTiles(config).map((t) => t.kind)).toEqual(['CALL_CONTACT', 'CALL_CONTACT', 'PHONE', 'MESSAGES', 'CAMERA', 'PHOTOS']);
  });
});
