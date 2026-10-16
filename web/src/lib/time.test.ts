import { describe, expect, it } from 'vitest';
import { dayLabel, describeDays, formatCountdown, formatHHmm, relativeTime } from './time';
import { toggleDay, describeSchedule } from './reminders';

const now = new Date(2026, 9, 16, 15, 0).getTime(); // Fri Oct 16 2026, 3:00 PM local
const MIN = 60_000;

describe('relativeTime', () => {
  it('handles recent times', () => {
    expect(relativeTime(now - 10_000, now)).toBe('just now');
    expect(relativeTime(now - MIN, now)).toBe('1 minute ago');
    expect(relativeTime(now - 4 * MIN, now)).toBe('4 minutes ago');
    expect(relativeTime(now - 60 * MIN, now)).toBe('1 hour ago');
    expect(relativeTime(now - 5 * 60 * MIN, now)).toBe('5 hours ago');
  });
  it('handles yesterday, this week and older', () => {
    expect(relativeTime(new Date(2026, 9, 15, 9, 12).getTime(), now)).toBe('yesterday at 9:12 AM');
    expect(relativeTime(new Date(2026, 9, 13, 18, 5).getTime(), now)).toBe('Tuesday at 6:05 PM');
    expect(relativeTime(new Date(2026, 2, 4).getTime(), now)).toBe('Mar 4');
    expect(relativeTime(new Date(2025, 2, 4).getTime(), now)).toBe('Mar 4, 2025');
  });
  it('tolerates clock drift and missing values', () => {
    expect(relativeTime(now + 2 * MIN, now)).toBe('just now');
    expect(relativeTime(null, now)).toBe('never');
  });
});

describe('formatting', () => {
  it('formatHHmm', () => {
    expect(formatHHmm('08:00')).toBe('8:00 AM');
    expect(formatHHmm('20:30')).toBe('8:30 PM');
    expect(formatHHmm('nope')).toBe('nope');
  });
  it('dayLabel', () => {
    expect(dayLabel(now - MIN, now)).toBe('Today');
    expect(dayLabel(now - 20 * 60 * MIN, now)).toBe('Yesterday');
  });
  it('describeDays', () => {
    expect(describeDays([])).toBe('Every day');
    expect(describeDays([1, 2, 3, 4, 5])).toBe('Weekdays');
    expect(describeDays([7, 6])).toBe('Weekends');
    expect(describeDays([5, 1, 3])).toBe('Mon, Wed, Fri');
  });
  it('formatCountdown', () => {
    expect(formatCountdown(15 * 60_000)).toBe('15:00');
    expect(formatCountdown(61_500)).toBe('1:02');
    expect(formatCountdown(-5)).toBe('0:00');
  });
});

describe('reminder schedule helpers', () => {
  it('toggleDay keeps at least one day and collapses all days to "every day"', () => {
    expect(toggleDay([], 3)).toEqual([1, 2, 4, 5, 6, 7]);
    expect(toggleDay([1, 2, 4, 5, 6, 7], 3)).toEqual([]);
    expect(toggleDay([2], 2)).toEqual([2]);
  });
  it('describeSchedule', () => {
    expect(describeSchedule({ id: 'r', title: 'Pill', detail: '', kind: 'MEDICATION', times: ['08:00', '20:00'], daysOfWeek: [], notifyFamilyIfMissed: false })).toBe('Every day at 8:00 AM, 8:00 PM');
    expect(describeSchedule({ id: 'r', title: 'Dr', detail: '', kind: 'APPOINTMENT', times: ['14:30'], daysOfWeek: [], date: '2026-11-03', notifyFamilyIfMissed: false })).toBe('Tue, Nov 3 at 2:30 PM');
  });
});
