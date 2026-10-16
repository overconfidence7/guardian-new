const TONES = ['#d9e9e3', '#f3e2cf', '#e4e0f0', '#f4dcdc', '#dfe8f3', '#efe8cf'];
const INKS = ['#1f5f57', '#7a4a17', '#4b3f7a', '#8a3434', '#2c4f7a', '#6a5a14'];

function hash(s: string): number {
  let h = 0;
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  const first = parts[0]!.replace(/[^\p{L}\p{N}]/gu, '');
  const last = parts.length > 1 ? parts[parts.length - 1]!.replace(/[^\p{L}\p{N}]/gu, '') : '';
  return ((first[0] ?? '') + (last[0] ?? '')).toUpperCase() || '?';
}

/** Photo or warm initials. Decorative by default: the name is usually printed next to it. */
export function Avatar({ name, src, size = 44, label }: { name: string; src?: string | null; size?: number; label?: string }) {
  const tone = hash(name) % TONES.length;
  const style = { width: size, height: size, fontSize: Math.round(size * 0.38), background: TONES[tone], color: INKS[tone] };
  return (
    <span className="avatar" style={style} role={label ? 'img' : undefined} aria-label={label} aria-hidden={label ? undefined : true}>
      {src ? <img src={src} alt="" loading="lazy" /> : initials(name)}
    </span>
  );
}
