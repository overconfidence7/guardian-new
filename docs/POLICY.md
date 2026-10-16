# Play policy and platform analysis

Researched 2026-10-16 against the live Google Play policy pages and Android developer documentation. Re-check every item before each Play submission; these pages change several times a year.

## 1. The headline risk: Play's Stalkerware rule

Play policy, Malware > Stalkerware (https://support.google.com/googleplay/android-developer/answer/9888380):

> Code that collects personal or sensitive user data from a device and transmits the data to a third party (enterprise or another individual) for monitoring purposes.

> Google Play prohibits apps from monitoring another individual ... unless the app is exclusively designed and marketed for parents to monitor their children ... or enterprise management for the monitoring of individual employees.

> These apps cannot be used to track anyone else (a spouse, for example) even with their knowledge and permission.

So **consent alone does not make adult-to-adult monitoring allowed**. An app whose purpose is "a child watches what a parent does on their phone" would be rejected or removed. The `isMonitoringTool` manifest flag (values `child_monitoring`, `enterprise_management`, `other`) does not create an allowed category for adult monitoring.

Products that do ship family scam protection on Play today (Truecaller Family Protection, Dec 2025; Aura Trusted Family Access, Sep 2026) show that **a person protecting themselves, who chooses trusted contacts to share specific safety alerts with**, is tolerated. That is the posture Guardian Mode is designed around, and it matches the product's dignity principle anyway.

### Design rules that follow (non-negotiable)

1. **The parent is the user.** The phone's protection belongs to the parent. Family members are "trusted helpers" the parent invites and can remove. Setup happens on the parent's phone, with the parent tapping every consent.
2. **Discrete safety alerts, never activity logs.** Guardian sends only the events in `EventType` (pause card shown, remote-access app, long call with unknown caller, help request, missed check-in, missed medication the parent agreed to share). It never sends app-usage history, unlock timelines, location, call lists, phone numbers, message content or screen content (except a screenshot the parent explicitly sends with Help).
3. **The parent can see and stop everything.** The transparency screen is generated from the live config, lists every feature and what it shares, shows the last 30 alerts sent, and lets the parent pause any feature or all of them. Helpers are told when the parent turns something off; nothing is hidden from either side.
4. **Persistent notification and unique icon** while protection runs (required of monitoring tools, and good practice regardless).
5. **Marketing in protection language.** Store listing, website and onboarding say "your phone, with your family in your corner." Never "monitor", "track" or "see what Mom is doing."
6. **No sideload distribution of a different build.** The stalkerware rule bans providing non-compliant functionality outside Play.

### Decision still open (owner: Nate, before the first Play submission)

Whether to declare `isMonitoringTool="other"`. Recommendation: do **not** declare it (Guardian is a self-protection app with user-chosen alerts, like Truecaller Family Protection), keep every safeguard above anyway, and ask Play developer policy support for a pre-review with the build and a demo video before going to production. The build reads `guardian.isMonitoringTool` from `android/gradle.properties`, so flipping it is one line.

## 2. Permissions and APIs

| Need | What we use | Policy status |
|---|---|---|
| Show launchable apps on the home screen | `<queries>` for `MAIN`/`LAUNCHER` intents | No declaration needed. **Do not** request `QUERY_ALL_PACKAGES`: launchers are not named as a permitted use. |
| See watched apps (bank, payment, crypto, remote access) | `<queries><package>` entries for the built-in catalog, plus the launcher query | No declaration needed. Family-added packages are visible if they have a launcher activity. |
| Know which app is in front | `PACKAGE_USAGE_STATS` (Usage access, granted in Settings) | Not in the restricted list; covered by prominent disclosure and Data safety. |
| Identify unknown callers | `CallScreeningService` with `ROLE_CALL_SCREENING` (API 29+) | No call-log permission needed. Google's docs: Android 10+ "eliminates the requirement to obtain the READ_CALL_LOG permission". |
| Know when a call is answered and ends | `READ_PHONE_STATE` + `TelephonyCallback.CallStateListener`, and the exempt `PHONE_STATE` manifest broadcast | No restriction found. Gives state only, no number. Cellular calls only; WhatsApp and other VoIP calls are invisible. |
| Treat the phone's own contacts as known | `READ_CONTACTS` (optional) | Normal runtime permission. From 2027-01-27 Play requires the Contact Picker for apps without a broad-contacts need; caller identification is a broad need, but we keep the feature working without the permission. |
| Call log | **Not requested** | The Call Log policy only allows default dialer or assistant handlers; "family locator" and launchers are listed as invalid uses. It also forbids deriving call-log data by "alternative methods", so Guardian never sends numbers or call lists off the phone. |
| SMS | **Not requested** | Same policy. |
| Accessibility API | **Not used** | Only for apps directly supporting people with disabilities; Android 16 also blocks granting it during calls with non-contacts. |
| Persistent protection service | Foreground service, type `specialUse`, subtype explained in the manifest property | Requires a Play Console FGS declaration with a description, user impact, and a demo video. `dataSync` is capped at 6 h/day on Android 15; `systemExempted` doesn't apply. The parent can stop it from the notification (Device and Network Abuse requires user-stoppable FGS). |
| Pause card over another app | Activity start from the default HOME app or with "Display over other apps" (`SYSTEM_ALERT_WINDOW`), falling back to a full-screen-intent / heads-up notification | Background activity launches are allowed for apps with `SYSTEM_ALERT_WINDOW` granted; AOSP also always allows the HOME app (undocumented; verified in Android 13 source). |
| Full-screen notifications | `USE_FULL_SCREEN_INTENT` | Not granted by default for non-calling/alarm apps on Android 14+. Requested at setup via `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`; Play Console declaration; must degrade gracefully. |
| Medication reminders on time | `SCHEDULE_EXACT_ALARM` with `canScheduleExactAlarms()` check, fallback to inexact windowed alarms | `USE_EXACT_ALARM` is only for alarm/timer/calendar apps, so we do not use it. |
| Detect new installs | Runtime-registered `PACKAGE_ADDED` receiver in the running service, plus `getChangedPackages(sequence)` on unlock and every 15 min | `PACKAGE_ADDED` is not exempt from implicit-broadcast limits, so no manifest receiver. |
| Help-me screenshot | `MediaProjection`, consent dialog every time | Matches "approve each time". FGS type `mediaProjection` only while capturing. |
| Push | Firebase Cloud Messaging | Standard. |

### Prominent disclosure and consent (User Data policy)

The disclosure must be in the app, in normal usage, describe the data and how it is shared, and get an affirmative tap before collection starts. Guardian's onboarding has one plain-language screen per feature group, each with "Turn on" / "Not now", before any permission dialog. Every screen is reachable later from the transparency screen.

### Data safety form (draft answers)

- Collected and **shared with the user's chosen family members**: App activity (app interactions: name of a payment or remote-access app when a pause card or alarm fires), Photos (a screenshot the user sends with Help), Device or other IDs (FCM token, install ID).
- Collected, not shared: App info and performance (crash logs).
- Not collected: location, messages, contacts list, call logs, audio, financial info, health info (medication names stay in the config the family wrote; they are not collected from the user).
- Encrypted in transit: yes. Users can request deletion: yes, in-app and on the web.

### Target audience

Adults only (18+). Families policy does not apply.

## 3. Platform realities that shape the build

- **Android 15 restricted settings:** sideloaded apps can't get Usage access, Display over other apps, or call roles without extra manual steps. Real users must install from Play; testers on sideloaded builds follow the "Allow restricted settings" path documented in `docs/TESTING.md`.
- **OEM battery killers** (Samsung, Xiaomi, Oppo) can stop the service. Onboarding asks for the battery-optimization exemption, and the check-in heartbeat lets the server notice a phone that has gone silent.
- **Built-in protections we lean on, not duplicate:** Android blocks turning off Play Protect and first-time sideloading during calls with non-contacts (Android 16); Google Scam Detection covers Pixel and Galaxy S26 call audio; Samsung One UI 9 plans warnings for apps installed shortly after a suspected scam call. Guardian's moat is the family layer, budget-phone coverage and the friction design.

## 4. Targets and versions (Oct 2026)

- Play requires target API 36 (Android 16) for new apps and updates from 2026-08-31 (extension to 2026-11-01). Guardian: `minSdk 29` (call screening), `targetSdk 36`, `compileSdk 37`.
- AGP 9.4.0, Gradle 9.8.1, Kotlin 2.4.20, Compose BOM 2026.09.00, Robolectric 4.17.

## Sources

- Stalkerware and Spyware: https://support.google.com/googleplay/android-developer/answer/9888380
- isMonitoringTool flag: https://support.google.com/googleplay/android-developer/answer/12955211
- Device and Network Abuse: https://support.google.com/googleplay/android-developer/answer/9888379
- Sensitive permissions: https://support.google.com/googleplay/android-developer/answer/9888170
- QUERY_ALL_PACKAGES: https://support.google.com/googleplay/android-developer/answer/10158779
- SMS and Call Log: https://support.google.com/googleplay/android-developer/answer/9047303
- Foreground service types: https://support.google.com/googleplay/android-developer/answer/13392821 and https://developer.android.com/develop/background-work/services/fgs/service-types
- User Data / prominent disclosure: https://support.google.com/googleplay/android-developer/answer/10144311
- Exact alarms: https://developer.android.com/about/versions/14/changes/schedule-exact-alarms
- Background activity starts: https://developer.android.com/guide/components/activities/background-starts
- Broadcast exceptions: https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
- Call screening: https://developer.android.com/develop/connectivity/telecom/dialer-app/screen-calls
- Target SDK: https://developer.android.com/google/play/requirements/target-sdk
- Android 15 behavior changes: https://developer.android.com/about/versions/15/behavior-changes-15
- Scam detection coverage: https://blog.google/security/staying-one-step-ahead-strengthening-androids-lead-in-scam-protection/
