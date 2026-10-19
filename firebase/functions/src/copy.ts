/*
 * Helper-facing notification wording, one entry per EventType.
 *
 * Voice: warm, calm and specific. Say what happened and what the parent already saw,
 * never alarm words ("scam", "fraud", "danger", "alert"), never anything the event does
 * not carry (no numbers, no content). The parent can read every line on the
 * transparency screen, so it must also read well to them.
 *
 * Event data keys come from the phone (core/TripwireEngine.kt) and the server:
 *   app, category, duringCall, minutesSinceCall, afterUnknownCall   (tripwires)
 *   minutes, ongoing                                                (LONG_UNKNOWN_CALL)
 *   note                                                            (HELP_REQUESTED)
 *   title, time                                                     (REMINDER_MISSED)
 *   feature, enabled                                                (PROTECTION_CHANGED_BY_PARENT)
 *   permission                                                      (PERMISSION_MISSING)
 *   reason ("silent"), deadline                                     (CHECK_IN_MISSED)
 */
import type { EventType, Severity } from "./lib/model.js";

export interface NotificationCopy {
  title: string;
  body: string;
}

export interface CopyContext {
  parentName: string;
}

export interface CopyEvent {
  type: EventType;
  severity: Severity;
  data: Readonly<Record<string, string>>;
}

const FALLBACK_PARENT = "Your parent";
const FALLBACK_APP = "an app";

/** Feature keys match core/Transparency.kt item keys. */
export const FEATURE_LABELS: Readonly<Record<string, string>> = {
  pause: "the pause before sending money",
  remoteAccess: "the remote-control app warning",
  longCall: "long-call notices",
  screening: "unknown caller screening",
  safeWord: "the family safe word card",
  checkIn: "the daily “I’m OK”",
  help: "the Help button",
  reminders: "reminders",
  all: "Guardian Mode",
};

/** Permission keys the phone reports in status.missing and PERMISSION_MISSING events. */
export const PERMISSION_LABELS: Readonly<Record<string, { name: string; effect: string }>> = {
  usageAccess: { name: "Usage access", effect: "pause cards can’t appear" },
  overlay: { name: "Display over other apps", effect: "pause cards may not appear on top" },
  callScreening: { name: "Call screening", effect: "unknown callers will ring as usual" },
  phoneState: { name: "Phone calls", effect: "Guardian can’t tell when a call is going on" },
  notifications: { name: "Notifications", effect: "reminders and Guardian’s notice can’t show" },
  exactAlarms: { name: "Alarms and reminders", effect: "reminders may arrive late" },
  fullScreenIntent: { name: "Full-screen notifications", effect: "reminder cards may show as small notifications" },
  battery: { name: "Unrestricted battery", effect: "the phone may stop Guardian in the background" },
  homeApp: { name: "Home app", effect: "the simple home screen isn’t showing" },
  contacts: { name: "Contacts", effect: "calls from the phone’s own contacts may be treated as unknown" },
};

function parentOf(ctx: CopyContext): string {
  const name = ctx.parentName.trim();
  return name.length > 0 ? name : FALLBACK_PARENT;
}

function possessive(name: string): string {
  return name.endsWith("s") ? `${name}’` : `${name}’s`;
}

function appOf(data: Readonly<Record<string, string>>): string {
  const app = data.app?.trim();
  return app && app.length > 0 ? app : FALLBACK_APP;
}

function intOf(value: string | undefined): number | null {
  if (value === undefined) return null;
  const n = Number.parseInt(value, 10);
  return Number.isFinite(n) && n >= 0 ? n : null;
}

function minutesPhrase(n: number): string {
  return n === 1 ? "1 minute" : `${n} minutes`;
}

function isTrue(value: string | undefined): boolean {
  return value === "true";
}

function capitalize(s: string): string {
  return s.length === 0 ? s : s[0]!.toUpperCase() + s.slice(1);
}

/** "11:00" → "11:00 AM", "14:30" → "2:30 PM". Unparseable input is returned unchanged. */
export function friendlyTime(hhmm: string): string {
  const match = /^(\d{1,2}):(\d{2})$/.exec(hhmm.trim());
  if (!match) return hhmm;
  const h = Number(match[1]);
  const m = match[2]!;
  if (h > 23 || Number(m) > 59) return hhmm;
  const suffix = h < 12 ? "AM" : "PM";
  const h12 = h % 12 === 0 ? 12 : h % 12;
  return `${h12}:${m} ${suffix}`;
}

function quote(s: string): string {
  return `“${s}”`;
}

type CopyBuilder = (p: string, data: Readonly<Record<string, string>>, severity: Severity) => NotificationCopy;

const BUILDERS: Record<EventType, CopyBuilder> = {
  LONG_UNKNOWN_CALL: (p, data, severity) => {
    const minutes = intOf(data.minutes);
    const duration = minutes === null ? "a while" : minutesPhrase(minutes);
    const ended = data.ongoing === "false";
    const extra = severity === "URGENT" ? " A money or remote-control app was opened during the call." : "";
    return ended
      ? {
          title: `${p} had a long call`,
          body: `${p} was on a call with an unknown number for ${duration}.${extra}`,
        }
      : {
          title: `${p} is on a long call`,
          body: `${p} has been on a call with an unknown number for ${duration}.${extra}`,
        };
  },

  PAUSE_SHOWN: (p, data) => {
    const app = appOf(data);
    if (isTrue(data.duringCall)) {
      return {
        title: `${p} paused before opening ${app}`,
        body: `${p} opened ${app} during a call with an unknown number, so the phone showed a pause card first.`,
      };
    }
    const minutes = intOf(data.minutesSinceCall);
    const when =
      minutes === null || minutes === 0
        ? "just after a call from an unknown number"
        : `${minutesPhrase(minutes)} after a call from an unknown number`;
    return {
      title: `${p} paused before opening ${app}`,
      body: `${p} opened ${app} ${when}, so the phone showed a pause card first.`,
    };
  },

  PAUSE_CALLED_FAMILY: (p, data) => ({
    title: `${p} chose to call family first`,
    body: `${p} saw the pause card for ${appOf(data)} and tapped to call family before going on.`,
  }),

  PAUSE_CONTINUED: (p, data) => {
    const app = appOf(data);
    return {
      title: `${p} went ahead with ${app}`,
      body: `${p} saw the pause card for ${app} and went ahead anyway. A quick call will tell you if everything’s all right.`,
    };
  },

  REMOTE_ACCESS_INSTALLED: (p, data) => {
    const app = appOf(data);
    const when = isTrue(data.afterUnknownCall) ? " It was installed during or soon after a call with an unknown number." : "";
    return {
      title: `${capitalize(app)} was installed on ${possessive(p)} phone`,
      body: `${capitalize(app)} lets someone control the phone from somewhere else.${when} ${p} saw a warning with your number on it. A quick call will tell you.`,
    };
  },

  REMOTE_ACCESS_OPENED: (p, data) => {
    const app = appOf(data);
    const when = isTrue(data.afterUnknownCall) ? " during or soon after a call with an unknown number" : "";
    return {
      title: `${p} opened ${app}`,
      body: `${capitalize(app)} lets someone control the phone from somewhere else. ${p} opened it${when} and saw a warning with your number on it. A quick call will tell you what’s going on.`,
    };
  },

  CONTROL_APP_INSTALLED: (p, data) => {
    const app = appOf(data);
    const when = isTrue(data.afterUnknownCall) ? " soon after a call with an unknown number" : "";
    return {
      title: `A new app can control ${possessive(p)} screen`,
      body: `${capitalize(app)} was installed${when} and can control the screen. ${p} saw a short warning about it on the phone. A quick call will tell you if they meant to install it.`,
    };
  },

  HELP_REQUESTED: (p, data) => {
    const note = data.note?.trim();
    return {
      title: `${p} asked for help`,
      body:
        note && note.length > 0
          ? `${p} sent you a picture of their screen with a note: ${quote(note)}`
          : `${p} sent you a picture of their screen.`,
    };
  },

  REMINDER_MISSED: (p, data) => {
    const title = data.title?.trim();
    const time = data.time?.trim();
    const what = title && title.length > 0 ? quote(title) : "a reminder";
    const due = time && time.length > 0 ? ` It was due at ${friendlyTime(time)}.` : "";
    return {
      title: `${p} hasn’t confirmed a reminder`,
      body: `${p} hasn’t tapped Done for ${what} yet.${due}`,
    };
  },

  PROTECTION_CHANGED_BY_PARENT: (p, data) => {
    const key = data.feature ?? "";
    const label = FEATURE_LABELS[key] ?? "a Guardian Mode feature";
    if (data.enabled === "true") {
      return {
        title: `${p} turned on ${label}`,
        body: `${p} turned ${label} back on.`,
      };
    }
    if (key === "all") {
      return {
        title: `${p} paused Guardian Mode`,
        body: `${p} paused Guardian Mode on their phone for a while. That’s up to them, and it turns back on by itself.`,
      };
    }
    return {
      title: `${p} turned off ${label}`,
      body: `${p} turned off ${label} on their phone. That’s up to them. You could ask how it’s going next time you talk.`,
    };
  },

  PERMISSION_MISSING: (p, data) => {
    const known = PERMISSION_LABELS[data.permission ?? ""];
    if (!known) {
      return {
        title: `${possessive(p)} phone needs a setting turned back on`,
        body: `A setting Guardian Mode needs is off on ${possessive(p)} phone. You can help turn it back on next time you talk.`,
      };
    }
    return {
      title: `${possessive(p)} phone needs a setting turned back on`,
      body: `${known.name} is off, so ${known.effect}. You can help ${p} turn it back on next time you talk.`,
    };
  },

  CHECK_IN_MISSED: (p, data) => {
    if (data.reason === "silent") {
      return {
        title: `${possessive(p)} phone has been quiet for a day`,
        body: `We haven’t heard from ${possessive(p)} phone in over 24 hours. It may be switched off or out of battery. A quick call will tell you.`,
      };
    }
    const deadline = data.deadline;
    const notYet = deadline
      ? `hadn’t been picked up by ${friendlyTime(deadline)} today`
      : "hasn’t been picked up yet today";
    return {
      title: `${possessive(p)} phone hasn’t been used yet today`,
      body: `${possessive(p)} phone ${notYet}. It may be nothing. A quick call will tell you.`,
    };
  },
};

export function notificationCopy(event: CopyEvent, ctx: CopyContext): NotificationCopy {
  return BUILDERS[event.type](parentOf(ctx), event.data, event.severity);
}

