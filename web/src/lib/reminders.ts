import type { Reminder } from './model';
import { describeDays, formatHHmm } from './time';

/** Toggle one ISO day; empty means every day, and at least one day stays selected. */
export function toggleDay(days: number[], day: number): number[] {
  const current = days.length === 0 ? [1, 2, 3, 4, 5, 6, 7] : days;
  const next = current.includes(day) ? current.filter((d) => d !== day) : [...current, day].sort((a, b) => a - b);
  if (next.length === 0) return current;
  return next.length === 7 ? [] : next;
}

export function describeSchedule(r: Reminder): string {
  const times = r.times.length ? r.times.map((t) => formatHHmm(t)).join(', ') : 'No time set';
  if (r.date) {
    const d = new Date(`${r.date}T12:00:00`);
    const day = Number.isNaN(d.getTime()) ? r.date : d.toLocaleDateString('en-US', { weekday: 'short', month: 'short', day: 'numeric' });
    return `${day} at ${times}`;
  }
  return `${describeDays(r.daysOfWeek)} at ${times}`;
}
