/** Pure helpers behind the home-screen tile editor. */
import type { DeviceConfig, HomeTile, InstalledApp, TileKind } from './model';
import { newId } from './ids';

export const TILE_KIND_INFO: Record<TileKind, { label: string; description: string; needsTarget: boolean }> = {
  CALL_CONTACT: { label: 'Call a person', description: 'Big photo tile that calls them in one tap', needsTarget: true },
  APP: { label: 'An app', description: 'Opens one app, like WhatsApp', needsTarget: true },
  PHONE: { label: 'Phone', description: 'The phone’s keypad and call log', needsTarget: false },
  MESSAGES: { label: 'Messages', description: 'Text messages', needsTarget: false },
  CAMERA: { label: 'Camera', description: 'Take a picture', needsTarget: false },
  PHOTOS: { label: 'Photos', description: 'Pictures on the phone', needsTarget: false },
  REMINDERS: { label: 'Reminders', description: 'Today’s pills and appointments', needsTarget: false },
  HELP: { label: 'Help', description: 'Sends you a picture of the screen, if they say yes', needsTarget: false },
  ALL_APPS: { label: 'All my apps', description: 'The apps you allowed, as a simple list', needsTarget: false },
};

/** Move an item; indexes are clamped. Returns a new array (the input is not changed). */
export function moveItem<T>(list: readonly T[], from: number, to: number): T[] {
  const copy = list.slice();
  if (from < 0 || from >= copy.length) return copy;
  const target = Math.max(0, Math.min(copy.length - 1, to));
  if (target === from) return copy;
  const [item] = copy.splice(from, 1);
  copy.splice(target, 0, item as T);
  return copy;
}

export function moveById<T extends { id: string }>(list: readonly T[], id: string, delta: number): T[] {
  const from = list.findIndex((t) => t.id === id);
  return from < 0 ? list.slice() : moveItem(list, from, from + delta);
}

export function removeById<T extends { id: string }>(list: readonly T[], id: string): T[] {
  return list.filter((t) => t.id !== id);
}

/**
 * Index an item dragged to pointer position `y` should land at, given the vertical
 * midpoints of every item (in list order, including the dragged one).
 */
export function dropIndex(midpoints: readonly number[], y: number, from: number): number {
  let index = 0;
  for (let i = 0; i < midpoints.length; i++) {
    if (i === from) continue;
    if (y > (midpoints[i] as number)) index++;
  }
  return index;
}

export function makeTile(kind: TileKind, opts: { target?: string; label?: string } = {}): HomeTile {
  return {
    id: newId('t'),
    kind,
    target: opts.target ?? '',
    label: opts.label ?? (TILE_KIND_INFO[kind].needsTarget ? '' : TILE_KIND_INFO[kind].label),
  };
}

export interface TilePerson {
  id: string;
  name: string;
  photoUrl?: string | null;
  phone: string;
}

/** Everyone a CALL_CONTACT tile can point at: family first, then contacts. */
export function callablePeople(config: Pick<DeviceConfig, 'family' | 'contacts'>): TilePerson[] {
  return [
    ...config.family.map((f) => ({ id: f.id, name: f.name, photoUrl: f.photoUrl, phone: f.phone })),
    ...config.contacts.map((c) => ({ id: c.id, name: c.name, photoUrl: c.photoUrl, phone: c.phone })),
  ];
}

/** Display label for a tile, falling back to its target's name. */
export function tileLabel(tile: HomeTile, config: Pick<DeviceConfig, 'family' | 'contacts'>, apps: readonly InstalledApp[] = []): string {
  if (tile.label.trim()) return tile.label;
  if (tile.kind === 'CALL_CONTACT') return callablePeople(config).find((p) => p.id === tile.target)?.name ?? 'Call';
  if (tile.kind === 'APP') return apps.find((a) => a.packageName === tile.target)?.label ?? tile.target;
  return TILE_KIND_INFO[tile.kind].label;
}

/** Problems worth telling the helper about (a tile pointing at a deleted contact, etc.). */
export function tileProblem(tile: HomeTile, config: Pick<DeviceConfig, 'family' | 'contacts'>, apps: readonly InstalledApp[]): string | null {
  if (tile.kind === 'CALL_CONTACT') {
    const person = callablePeople(config).find((p) => p.id === tile.target);
    if (!person) return 'This person was removed. Pick someone else or remove the tile.';
    if (!person.phone.trim()) return `${person.name} has no phone number yet.`;
  }
  if (tile.kind === 'APP' && apps.length > 0 && !apps.some((a) => a.packageName === tile.target)) {
    return 'This app isn’t on the phone right now.';
  }
  return null;
}

/** A friendly first layout, used when a family starts from an empty home screen. */
export function starterTiles(config: Pick<DeviceConfig, 'family' | 'contacts'>): HomeTile[] {
  const people = config.family.slice(0, 2).map((f) => makeTile('CALL_CONTACT', { target: f.id, label: f.name }));
  return [...people, makeTile('PHONE'), makeTile('MESSAGES'), makeTile('CAMERA'), makeTile('PHOTOS')];
}
