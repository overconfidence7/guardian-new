/*
 * Three-way merge for config saves. Two helpers (or a helper and the parent's phone)
 * can edit the same config at once. We keep the version the editor started from
 * (`base`), the editor's draft (`local`) and the latest document (`remote`), and for
 * every leaf take the local value only where the editor actually changed it.
 * Arrays are treated as whole values: tiles, reminders and contacts are edited as lists.
 */

type Json = unknown;

function isPlainObject(v: Json): v is Record<string, Json> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

export function deepEqual(a: Json, b: Json): boolean {
  if (a === b) return true;
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && a.every((x, i) => deepEqual(x, b[i]));
  }
  if (isPlainObject(a) && isPlainObject(b)) {
    const ka = Object.keys(a).filter((k) => a[k] !== undefined);
    const kb = Object.keys(b).filter((k) => b[k] !== undefined);
    return ka.length === kb.length && ka.every((k) => deepEqual(a[k], b[k]));
  }
  return false;
}

export function threeWayMerge<T>(base: T, local: T, remote: T): T {
  if (deepEqual(local, base)) return remote;
  if (deepEqual(remote, base)) return local;
  if (isPlainObject(base) && isPlainObject(local) && isPlainObject(remote)) {
    const keys = new Set([...Object.keys(base), ...Object.keys(local), ...Object.keys(remote)]);
    const out: Record<string, Json> = {};
    for (const k of keys) {
      const v = threeWayMerge(base[k], local[k], remote[k]);
      if (v !== undefined) out[k] = v;
    }
    return out as T;
  }
  // Both sides changed a leaf or an array: the person pressing Save wins.
  return local;
}

/** Dotted paths that differ between two configs (for "You changed 3 things"). */
export function changedPaths(a: Json, b: Json, prefix = ''): string[] {
  if (deepEqual(a, b)) return [];
  if (isPlainObject(a) && isPlainObject(b)) {
    const keys = new Set([...Object.keys(a), ...Object.keys(b)]);
    return [...keys].flatMap((k) => changedPaths(a[k], b[k], prefix ? `${prefix}.${k}` : k));
  }
  return [prefix || '(root)'];
}

/** Remove `undefined` values recursively so Firestore accepts the document. */
export function stripUndefined<T>(value: T): T {
  if (Array.isArray(value)) return value.map((v) => stripUndefined(v)) as T;
  if (isPlainObject(value)) {
    const out: Record<string, Json> = {};
    for (const [k, v] of Object.entries(value)) if (v !== undefined) out[k] = stripUndefined(v);
    return out as T;
  }
  return value;
}
