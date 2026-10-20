import { describe, expect, it } from 'vitest';
import { describeEvent, possessive, type EventLike } from './eventText';
import { EVENT_TYPES, SERVER_EVENT_TYPES, type AnyEventType, type EventType } from './model';

const ev = (type: EventType, data: Record<string, string> = {}, severity: EventLike['severity'] = 'NOTICE'): EventLike => ({ type, data, severity });

describe('describeEvent', () => {
  it('has calm text for every EventType, with and without data', () => {
    for (const type of EVENT_TYPES) {
      for (const data of [{} as Record<string, string>, { app: 'Chase', minutes: '20', note: 'Hi', title: 'Pill', time: '08:00', feature: 'pause', permission: 'usageAccess', deadline: '11:00' }]) {
        for (const severity of ['INFO', 'NOTICE', 'URGENT'] as const) {
          const t = describeEvent(ev(type, data, severity), 'Mom');
          expect(t.title.length, type).toBeGreaterThan(5);
          expect(t.body.length, type).toBeGreaterThan(10);
          const all = `${t.title} ${t.body} ${t.suggestion ?? ''}`.toLowerCase();
          for (const banned of ['scam', 'fraud', 'danger', 'alert', 'undefined', 'null', 'nan']) {
            expect(all, `${type} contains "${banned}"`).not.toContain(banned);
          }
        }
      }
    }
  });

  it('matches the spec example for a pause after a call', () => {
    const t = describeEvent(ev('PAUSE_SHOWN', { app: 'Chase', duringCall: 'false', minutesSinceCall: '4' }), 'Mom');
    expect(t.body).toBe('Mom opened Chase 4 minutes after a call from an unknown number. A pause card was shown.');
  });

  it('describes a pause during a live call', () => {
    const t = describeEvent(ev('PAUSE_SHOWN', { app: 'Zelle', duringCall: 'true', minutesSinceCall: '0' }, 'URGENT'), 'Mom');
    expect(t.body).toBe('Mom opened Zelle during a call with an unknown number. A pause card was shown.');
    expect(t.suggestion).toMatch(/call/i);
  });

  it('uses singular minutes and "just after" for zero', () => {
    expect(describeEvent(ev('PAUSE_SHOWN', { app: 'Venmo', minutesSinceCall: '1' }), 'Dad').body).toContain('1 minute after');
    expect(describeEvent(ev('PAUSE_SHOWN', { app: 'Venmo', minutesSinceCall: '0' }), 'Dad').body).toContain('just after a call');
  });

  it('long calls: ongoing vs ended, urgent mentions the risky app', () => {
    expect(describeEvent(ev('LONG_UNKNOWN_CALL', { minutes: '20', ongoing: 'true' }), 'Mom').body).toBe('Mom has been on a call with an unknown number for 20 minutes.');
    expect(describeEvent(ev('LONG_UNKNOWN_CALL', { minutes: '25', ongoing: 'false' }), 'Mom').title).toBe('Mom had a long call');
    expect(describeEvent(ev('LONG_UNKNOWN_CALL', { minutes: '20' }, 'URGENT'), 'Mom').body).toContain('money or remote-control app');
  });

  it('remote-access install mentions the unknown call only when relevant', () => {
    const a = describeEvent(ev('REMOTE_ACCESS_INSTALLED', { app: 'AnyDesk', afterUnknownCall: 'true' }, 'URGENT'), 'Mom');
    expect(a.title).toBe('AnyDesk was installed on Mom’s phone');
    expect(a.body).toContain('during or soon after a call with an unknown number');
    const b = describeEvent(ev('REMOTE_ACCESS_INSTALLED', { app: 'AnyDesk', afterUnknownCall: 'false' }), 'Mom');
    expect(b.body).not.toContain('unknown number');
  });

  it('help, reminders, protection, permissions and check-ins', () => {
    expect(describeEvent(ev('HELP_REQUESTED', { note: 'Is this real?' }), 'Mom').body).toBe('Mom sent you a picture of their screen: “Is this real?”');
    expect(describeEvent(ev('REMINDER_MISSED', { title: 'Blood pressure pill', time: '20:00' }), 'Mom').body).toBe('Mom hasn’t tapped Done for “Blood pressure pill” yet. It was due at 8:00 PM.');
    expect(describeEvent(ev('PROTECTION_CHANGED_BY_PARENT', { feature: 'longCall', enabled: 'false' }), 'Mom').title).toBe('Mom turned off long-call notices');
    expect(describeEvent(ev('PROTECTION_CHANGED_BY_PARENT', { feature: 'all' }), 'Mom').title).toBe('Mom paused Guardian Mode');
    expect(describeEvent(ev('PERMISSION_MISSING', { permission: 'callScreening' }), 'Mom').body).toContain('Call screening is off, so unknown callers will ring as usual');
    expect(describeEvent(ev('CHECK_IN_MISSED', { reason: 'silent' }, 'URGENT'), 'Mom').title).toBe('Mom’s phone has been quiet for a day');
    expect(describeEvent(ev('CHECK_IN_MISSED', { deadline: '11:00' }), 'Mom').body).toContain('by 11:00 AM');
  });

  it('falls back to a neutral name', () => {
    expect(describeEvent(ev('HELP_REQUESTED'), '  ').title).toBe('Your parent would like some help');
  });

  it('possessive', () => {
    expect(possessive('Mom')).toBe('Mom’s');
    expect(possessive('James')).toBe('James’');
  });

  it('family notices match the push wording (functions/src/copy.ts)', () => {
    const notice = (type: AnyEventType, data: Record<string, string>, severity: EventLike['severity'] = 'NOTICE') =>
      describeEvent({ type, data, severity }, 'Mom');
    expect(notice('HELPER_CHANGED', { change: 'added', name: 'David' }, 'URGENT').body).toBe(
      'Mom said yes to David on the phone. David can now change Mom’s settings and will get these notices too.',
    );
    expect(notice('HELPER_CHANGED', { change: 'removed', name: 'David', by: 'Sarah' }).body).toBe(
      'Sarah removed David from the family, so David can’t see or change anything on Mom’s phone now.',
    );
    expect(notice('CHANGE_REQUEST_ANSWERED', { byName: 'David', approved: 'false', what: 'turn off long-call notices' }).body).toBe(
      'David asked to turn off long-call notices. Mom said no on the phone, so nothing changed.',
    );
    for (const type of SERVER_EVENT_TYPES) {
      for (const data of [{} as Record<string, string>, { change: 'left', name: 'David', by: 'Sarah', byName: 'David', approved: 'true', what: 'trust a remote-control app' }]) {
        const t = notice(type, data, 'URGENT');
        const all = `${t.title} ${t.body} ${t.suggestion ?? ''}`;
        expect(all).toContain('Mom');
        expect(all).not.toMatch(/\b(scam|fraud|danger|alert|monitor\w*|track\w*|undefined|null)\b/i);
        expect(all).not.toMatch(/ [-–—] |—/);
      }
    }
  });

  it('a type from a newer server still reads calmly', () => {
    expect(describeEvent({ type: 'SOMETHING_NEW' as AnyEventType, data: {}, severity: 'NOTICE' }, 'Mom').title).toBe('News from Mom’s phone');
  });
});
