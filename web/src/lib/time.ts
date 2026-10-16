/** Human, calm time formatting. All functions take `now` so they are testable. */

const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

export function startOfDay(ms: number): number {
  const d = new Date(ms);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

export function formatTimeOfDay(ms: number, locale?: string): string {
  return new Date(ms).toLocaleTimeString(locale ?? 'en-US', { hour: 'numeric', minute: '2-digit' });
}

/**
 * "just now", "4 minutes ago", "3 hours ago", "yesterday at 9:12 AM",
 * "Tuesday at 9:12 AM" (within a week), "Mar 4" (this year), "Mar 4, 2025".
 */
export function relativeTime(ms: number | null | undefined, now: number = Date.now(), locale = 'en-US'): string {
  if (ms == null || !Number.isFinite(ms)) return 'never';
  const diff = now - ms;
  if (diff < 0) {
    // Phone clocks drift; a time slightly in the future is "just now".
    return diff > -5 * MINUTE ? 'just now' : formatTimeOfDay(ms, locale);
  }
  if (diff < MINUTE) return 'just now';
  if (diff < HOUR) {
    const m = Math.floor(diff / MINUTE);
    return `${m} minute${m === 1 ? '' : 's'} ago`;
  }
  const today = startOfDay(now);
  if (diff < 12 * HOUR || ms >= today) {
    const h = Math.floor(diff / HOUR);
    return `${h} hour${h === 1 ? '' : 's'} ago`;
  }
  if (ms >= today - DAY) return `yesterday at ${formatTimeOfDay(ms, locale)}`;
  if (ms >= today - 6 * DAY) {
    const weekday = new Date(ms).toLocaleDateString(locale, { weekday: 'long' });
    return `${weekday} at ${formatTimeOfDay(ms, locale)}`;
  }
  const d = new Date(ms);
  const sameYear = d.getFullYear() === new Date(now).getFullYear();
  return d.toLocaleDateString(locale, sameYear ? { month: 'short', day: 'numeric' } : { month: 'short', day: 'numeric', year: 'numeric' });
}

/** "08:00" -> "8:00 AM". Returns the input if it isn't HH:mm. */
export function formatHHmm(hhmm: string, locale = 'en-US'): string {
  const m = /^(\d{1,2}):(\d{2})$/.exec(hhmm);
  if (!m) return hhmm;
  const d = new Date(2000, 0, 1, Number(m[1]), Number(m[2]));
  return d.toLocaleTimeString(locale, { hour: 'numeric', minute: '2-digit' });
}

/** Group label for a feed: "Today", "Yesterday", "Monday, Oct 12". */
export function dayLabel(ms: number, now: number = Date.now(), locale = 'en-US'): string {
  const today = startOfDay(now);
  if (ms >= today) return 'Today';
  if (ms >= today - DAY) return 'Yesterday';
  return new Date(ms).toLocaleDateString(locale, { weekday: 'long', month: 'short', day: 'numeric' });
}

export const DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'] as const;

/** ISO days (1 = Monday) to "Every day", "Weekdays", "Mon, Wed, Fri". */
export function describeDays(days: number[]): string {
  const set = [...new Set(days)].filter((d) => d >= 1 && d <= 7).sort((a, b) => a - b);
  if (set.length === 0 || set.length === 7) return 'Every day';
  if (set.join() === '1,2,3,4,5') return 'Weekdays';
  if (set.join() === '6,7') return 'Weekends';
  return set.map((d) => DAY_NAMES[d - 1]).join(', ');
}
