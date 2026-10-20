/*
 * Helper-facing text for every EventType. Title and body match the push notification
 * wording in firebase/functions/src/copy.ts (same voice: warm, calm, specific; never
 * "scam", "fraud", "danger" or "alert"; nothing the event doesn't carry). The dashboard
 * adds an optional calm suggestion of what to do.
 */
import type { AnyEventType, Severity } from './model';
import { featureLabel, permissionLabel } from './features';
import { formatHHmm } from './time';

export interface EventLike {
  type: AnyEventType;
  severity: Severity;
  data: Record<string, string>;
}

export interface EventText {
  title: string;
  body: string;
  suggestion?: string;
  icon: 'pause' | 'phone' | 'shield' | 'hand' | 'bell' | 'sun' | 'settings' | 'remote' | 'check';
}

const isTrue = (v: string | undefined) => v === 'true';

function intOf(v: string | undefined): number | null {
  if (v === undefined) return null;
  const n = Number.parseInt(v, 10);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

const minutesPhrase = (n: number) => (n === 1 ? '1 minute' : `${n} minutes`);
const capitalize = (s: string) => (s ? s[0]!.toUpperCase() + s.slice(1) : s);
const quote = (s: string) => `“${s}”`;

function appOf(d: Record<string, string>): string {
  const a = d.app?.trim();
  return a ? a : 'an app';
}

/** Possessive that reads well for "Mom" and "James" alike. */
export function possessive(name: string): string {
  return name.endsWith('s') ? `${name}’` : `${name}’s`;
}

export function describeEvent(event: EventLike, parentName: string): EventText {
  const p = parentName.trim() || 'Your parent';
  const d = event.data ?? {};
  const urgent = event.severity === 'URGENT';
  const callNow = `A quick call to ${p} usually helps. Calls from family always ring through.`;

  switch (event.type) {
    case 'LONG_UNKNOWN_CALL': {
      const minutes = intOf(d.minutes);
      const duration = minutes === null ? 'a while' : minutesPhrase(minutes);
      const ended = d.ongoing === 'false';
      const extra = urgent ? ' A money or remote-control app was opened during the call.' : '';
      return ended
        ? { title: `${p} had a long call`, body: `${p} was on a call with an unknown number for ${duration}.${extra}`, icon: 'phone' }
        : {
            title: `${p} is on a long call`,
            body: `${p} has been on a call with an unknown number for ${duration}.${extra}`,
            suggestion: urgent ? callNow : undefined,
            icon: 'phone',
          };
    }
    case 'PAUSE_SHOWN': {
      const app = appOf(d);
      let when: string;
      if (isTrue(d.duringCall)) when = 'during a call with an unknown number';
      else {
        const m = intOf(d.minutesSinceCall);
        when = m === null || m === 0 ? 'just after a call from an unknown number' : `${minutesPhrase(m)} after a call from an unknown number`;
      }
      return {
        title: `${p} paused before opening ${app}`,
        body: `${p} opened ${app} ${when}. A pause card was shown.`,
        suggestion: urgent ? callNow : undefined,
        icon: 'pause',
      };
    }
    case 'PAUSE_CALLED_FAMILY':
      return {
        title: `${p} chose to call family first`,
        body: `After the pause card for ${appOf(d)}, ${p} tapped to call family before going on.`,
        icon: 'check',
      };
    case 'PAUSE_CONTINUED': {
      const app = appOf(d);
      return {
        title: `${p} continued to ${app}`,
        body: `${p} saw the pause card for ${app} and chose to keep going. A quick call might help.`,
        icon: 'pause',
      };
    }
    case 'REMOTE_ACCESS_INSTALLED': {
      const app = appOf(d);
      const when = isTrue(d.afterUnknownCall) ? ' during or soon after a call with an unknown number' : '';
      return {
        title: `${capitalize(app)} was installed on ${possessive(p)} phone`,
        body: `${capitalize(app)} lets someone control the phone from another device. It was installed${when}. ${p} saw a warning with your number.`,
        suggestion: urgent ? callNow : undefined,
        icon: 'remote',
      };
    }
    case 'REMOTE_ACCESS_OPENED': {
      const app = appOf(d);
      const when = isTrue(d.afterUnknownCall) ? ' during or soon after a call with an unknown number' : '';
      return {
        title: `${p} opened ${app}`,
        body: `${capitalize(app)} lets someone control the phone from another device. ${p} opened it${when} and saw a warning with your number.`,
        suggestion: urgent ? callNow : 'If someone in the family uses it to help, you can mark it as trusted under Protection.',
        icon: 'remote',
      };
    }
    case 'CONTROL_APP_INSTALLED': {
      const app = appOf(d);
      const when = isTrue(d.afterUnknownCall) ? ' soon after a call with an unknown number' : '';
      return {
        title: `A new app can control ${possessive(p)} screen`,
        body: `${capitalize(app)} was installed${when} and can control the screen. ${p} saw a short warning.`,
        suggestion: urgent ? callNow : undefined,
        icon: 'remote',
      };
    }
    case 'HELP_REQUESTED': {
      const note = d.note?.trim();
      return {
        title: `${p} would like some help`,
        body: note ? `${p} sent you a picture of their screen: ${quote(note)}` : `${p} sent you a picture of their screen.`,
        suggestion: 'Give them a call when you can.',
        icon: 'hand',
      };
    }
    case 'REMINDER_MISSED': {
      const title = d.title?.trim();
      const time = d.time?.trim();
      const what = title ? quote(title) : 'a reminder';
      const due = time ? ` It was due at ${formatHHmm(time)}.` : '';
      return { title: `${p} hasn’t confirmed a reminder`, body: `${p} hasn’t tapped Done for ${what} yet.${due}`, icon: 'bell' };
    }
    case 'PROTECTION_CHANGED_BY_PARENT': {
      const key = d.feature ?? '';
      const label = featureLabel(key);
      if (isTrue(d.enabled)) return { title: `${p} turned on ${label}`, body: `${p} switched ${label} back on.`, icon: 'shield' };
      if (key === 'all') {
        return {
          title: `${p} paused Guardian Mode`,
          body: `${p} paused Guardian Mode on their phone for a while. That’s their choice, and it turns back on by itself.`,
          icon: 'shield',
        };
      }
      return {
        title: `${p} turned off ${label}`,
        body: `${p} switched off ${label} on their phone. That’s their choice. You could ask how it’s going next time you talk.`,
        icon: 'shield',
      };
    }
    case 'PERMISSION_MISSING': {
      const known = permissionLabel(d.permission ?? '');
      return {
        title: `${possessive(p)} phone needs a setting turned back on`,
        body: known
          ? `${known.name} is off, so ${known.effect}. You can help ${p} turn it back on next time you talk.`
          : `A setting Guardian Mode needs is off on ${possessive(p)} phone. You can help turn it back on next time you talk.`,
        icon: 'settings',
      };
    }
    case 'CHECK_IN_MISSED': {
      if (d.reason === 'silent') {
        return {
          title: `${possessive(p)} phone has been quiet for a day`,
          body: `${possessive(p)} phone hasn’t been in touch for over 24 hours. It may be switched off or out of battery. A call could help.`,
          icon: 'sun',
        };
      }
      const by = d.deadline ? ` by ${formatHHmm(d.deadline)}` : '';
      return {
        title: `${possessive(p)} phone hasn’t been used yet today`,
        body: `${possessive(p)} phone hasn’t been picked up yet today${by}. It may be nothing, but a quick call will tell you.`,
        icon: 'sun',
      };
    }
    case 'HELPER_CHANGED': {
      const name = d.name?.trim() || 'Someone';
      const by = d.by?.trim();
      switch (d.change) {
        case 'added':
          return {
            title: `${name} is now helping with ${possessive(p)} phone`,
            body: `${p} said yes to ${name} on the phone. ${name} can now change ${possessive(p)} settings and will get these notices too.`,
            suggestion: `If you don’t know ${name}, give ${p} a call. They can remove a helper from “What my family sees”.`,
            icon: 'shield',
          };
        case 'declined':
          return { title: `${p} said no to ${name}`, body: `${name} asked to help with ${possessive(p)} phone, and ${p} said no. Nothing changed.`, icon: 'shield' };
        case 'left':
          return {
            title: `${name} stopped helping with ${possessive(p)} phone`,
            body: `${name} left the family, so they can’t see or change anything on ${possessive(p)} phone now.`,
            icon: 'shield',
          };
        default:
          return {
            title: `${name} no longer helps with ${possessive(p)} phone`,
            body: `${by && by !== name ? by : p} removed ${name} from the family, so ${name} can’t see or change anything on ${possessive(p)} phone now.`,
            icon: 'shield',
          };
      }
    }
    case 'CHANGE_REQUEST_ANSWERED': {
      const by = d.byName?.trim() || 'A helper';
      const what = d.what?.trim() || 'change a protection setting';
      return isTrue(d.approved)
        ? {
            title: `${p} said yes to a change from ${by}`,
            body: `${by} asked to ${what}. ${p} said yes on the phone, so the change is in place.`,
            suggestion: urgent ? `If that wasn’t someone in the family, give ${p} a call.` : undefined,
            icon: 'shield',
          }
        : { title: `${p} said no to a change from ${by}`, body: `${by} asked to ${what}. ${p} said no on the phone, so nothing changed.`, icon: 'shield' };
    }
    case 'PHONE_LEFT':
      return d.by
        ? { title: `${possessive(p)} phone was taken out of the family`, body: `${d.by} removed ${possessive(p)} phone from the family, so you won’t hear from it anymore.`, icon: 'settings' }
        : {
            title: `${p} stopped using Guardian Mode`,
            body: `${p} took their phone out of the family, so you won’t hear from it anymore. A quick call will tell you if that was the plan.`,
            icon: 'settings',
          };
    default:
      // A type from a newer server: still say something calm instead of nothing.
      return { title: `News from ${possessive(p)} phone`, body: `There’s an update about ${possessive(p)} phone.`, icon: 'bell' };
  }
}

export const SEVERITY_LABEL: Record<Severity, string> = {
  URGENT: 'Needs you now',
  NOTICE: 'Good to know',
  INFO: 'For your information',
};
