/** Short, URL-safe random ids for tiles, contacts, reminders and photos. */
export function newId(prefix = ''): string {
  const bytes = new Uint8Array(10);
  crypto.getRandomValues(bytes);
  const alphabet = '0123456789abcdefghijklmnopqrstuvwxyz';
  let s = '';
  for (const b of bytes) s += alphabet[b % alphabet.length];
  return prefix ? `${prefix}_${s}` : s;
}

/** "K7M2QX" -> "K7M 2QX" for reading aloud. */
export function formatCode(code: string): string {
  const c = code.trim().toUpperCase();
  return c.length === 6 ? `${c.slice(0, 3)} ${c.slice(3)}` : c;
}

/** Invite codes are 6 chars from this alphabet (docs/API.md). */
export const INVITE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

export function normalizeCode(input: string): string {
  return input.toUpperCase().replace(/[^A-Z0-9]/g, '');
}

export function isValidCode(code: string): boolean {
  return code.length === 6 && [...code].every((ch) => INVITE_ALPHABET.includes(ch));
}
