export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  const first = parts[0]!.replace(/[^\p{L}\p{N}]/gu, '');
  const last = parts.length > 1 ? parts[parts.length - 1]!.replace(/[^\p{L}\p{N}]/gu, '') : '';
  return ((first[0] ?? '') + (last[0] ?? '')).toUpperCase() || '?';
}
