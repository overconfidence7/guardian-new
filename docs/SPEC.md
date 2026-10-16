# Guardian Mode: product spec

**One line:** your phone, with your family in your corner.

Guardian Mode turns a parent's Android phone into a calm, simple phone that their family can set up from afar, and that slows a scam down at the moments scams actually happen. The parent owns it, sees everything it shares, and can switch any part off.

## 1. People

- **Parent** (the user of the phone). Typically 70+, on a hand-me-down or budget Android phone. Wants: a phone that is easy, the people they love one tap away, not to feel watched or stupid.
- **Helper** (adult child, 40–60). Pays. Lives elsewhere. Uses an iPhone or Android or laptop. Wants: to fix Mom's phone without driving over, and to get a call *before* the gift cards are bought, not after.
- **Up to 4 helpers and 2 parent phones** per family plan.

## 2. What "good" looks like

These are the bars every milestone is judged against.

1. **A parent who never saw the app before can call their daughter within 5 seconds of picking up the phone.** Big photo tiles, one tap, no menus.
2. **A helper can change Mom's home screen from their iPhone and see it change on her phone within 10 seconds** while her phone is online.
3. **The pause card appears within 2 seconds of a bank, payment, gift-card, crypto or remote-access app opening** during or within 30 minutes of a call from an unknown number, and "Call Sarah" works with one tap.
4. **Zero false alarms from family calls.** Calls from family, contacts and allowlisted numbers never arm a tripwire.
5. **The parent can always answer "what does my family see?"** from one tap on the home screen, and the answer is generated from the live settings.
6. **Nothing leaves the phone that is not in the event list.** No numbers, no messages, no usage history, no location.
7. **Works on a 4-year-old budget phone** (Android 10, 3 GB RAM, Samsung/Motorola/Moto G/Galaxy A) with no noticeable battery cost: under 2% per day attributable to Guardian.
8. **Accessible by default:** every touch target at least 64 dp, text scale starts at 130% of Android's base, WCAG AA contrast (AAA in high-contrast mode), TalkBack labels on everything, no time-limited interactions for the parent.
9. **Calm voice.** Copy never uses "scam", "fraud", "danger" or "alert" to the parent. It says "Let's pause", "Did someone on the phone ask you to do this?", "It's OK to hang up."
10. **Play-policy clean** as laid out in `POLICY.md`.

## 3. Features (MVP scope marked ★)

### Simple, set up remotely

**★ F1. Launcher.** Guardian is the default home app. Home shows, top to bottom:
- Time and date, very large.
- The photo card ("Today from Emma"): latest photo a helper sent, with sender and caption. Tap for full screen; swipe for earlier photos.
- A grid (2 columns by default) of tiles configured by helpers: call a person (photo + name), Phone, Messages, Camera, Photos, an app, Reminders, Help.
- The "Next reminder" strip when a reminder is due within 2 hours.
- A small shield chip "Protected · What my family sees" that opens the transparency screen.
- "All my apps" at the bottom shows only the apps helpers allowed, as big rows, alphabetical.

The parent can always leave Guardian: Settings > Home app is reachable through "All my apps" > "Phone settings", and the transparency screen has "Stop using Guardian Mode" with plain instructions.

**★ F2. Remote setup.** Helpers edit from the web dashboard (works on iPhone, Android, desktop). Everything in `DeviceConfig`: tiles, contacts with photos, family list and primary helper, font size, theme, allowed apps (from the list the phone reports of installed launchable apps), protection settings, check-in time, reminders. Changes apply live. Every change is attributed ("Sarah changed text size") and visible on the phone's transparency screen under "Recent changes".

**★ F3. Photo card.** Helpers upload photos (JPEG/HEIC/PNG, resized server-side to 1600 px). Newest shows on the home screen for 24 h, then rotates through the last 20.

### Scam friction, not surveillance

**★ F4. Pause tripwires.** When a payment, gift-card, crypto or bank app (built-in list plus family additions) comes to the front **during** an answered call with an unknown number, or **within N minutes (default 30)** after one, Guardian shows a full-screen card:

> **Let's pause for a moment.**
> Did someone on the phone ask you to do this?
> Real banks, police and Medicare never ask you to move money, buy gift cards or send crypto.
> [ Call Sarah first ] (big, primary)
> [ I'm fine, continue ] (secondary)

The card shows once per app category per call; "continue" silences that app until the next unknown call. Helpers get a notice ("Mom opened Chase 4 minutes after a call from an unknown number. A pause card was shown."); during a live call it is urgent.

**★ F5. Remote-access alarm.** Installing or opening a remote-control app (AnyDesk, TeamViewer QuickSupport, RustDesk, Splashtop SOS, Zoho Assist, ScreenConnect, AirDroid and more) shows a full-screen warning ("Real companies never ask you to install this. If someone on the phone asked, hang up and call Sarah.") with "Call Sarah", "Remove this app" (opens the uninstall dialog) and "I trust this". Helpers get an urgent alert on install. Helpers can mark tools they themselves use as trusted. Any newly installed app that can control the screen (declares an accessibility service) gets the same warning in a milder form.

**★ F6. Unknown-caller screening.** Guardian takes the call-screening role. Modes: Off, Silence unknown callers (default: phone doesn't ring, call goes to voicemail if not answered, shows in the call log), Send unknown callers to voicemail. Always rings: phone contacts, Guardian contacts, family, and the helper-curated allowlist (pharmacy, doctor, church office). A second call from the same number within 3 minutes rings through (the "call twice if urgent" convention). Calls whose caller ID fails network verification never ring.

**★ F7. Long-call alert.** An answered call with an unknown number passing N minutes (default 20) notifies helpers once: "Mom has been on a call with an unknown number for 20 minutes." No number is shared. It is urgent if a money or remote-access app was opened during the call.

**★ F8. Family safe word.** After an answered unknown call of 30 s or more, a gentle card: "If anyone said they're family and in trouble, ask for the family safe word. Still not sure? [ Call Sarah ] [ Call David ]". The word itself is set by the family and can be revealed on the phone by tapping "Show our safe word"; it is never sent anywhere.

### Everyday care

**★ F9. Passive "I'm OK".** The phone sends a heartbeat (just a timestamp) when it is unlocked or picked up, at most every 15 minutes. If there's no heartbeat today by the deadline (default 11:00 parent-local time), helpers get "Mom's phone hasn't been used yet today." Also fires if the phone has been completely silent (off, dead battery) for 24 h. No buttons for the parent.

**F10. Help me.** A Help tile and a notification action. The parent taps it, Android asks "Start recording or casting?" (every time), Guardian captures one screenshot, shows it to the parent ("Send this to Sarah?"), and on yes uploads it with an optional voice-free note chosen from big buttons ("I'm stuck", "Is this real?", "Call me"). Helpers see it in the feed and can call back. Live screen share is a post-MVP item (WebRTC, approved each time).

**F11. Reminders.** Medication and appointment reminders with big full-screen cards at the set time: title, detail ("One white tablet with water"), [ Done ] [ Remind me in 10 minutes ]. If the helper ticked "Tell me if missed" and the parent hasn't tapped Done within 60 minutes, helpers get a notice.

**★ F12. Transparency screen.** "What my family can see". Lists: who your helpers are (names, photos; "Remove" with confirmation), every feature with what it shares and what it never shares, on/off switch for each (turning one off tells helpers), "Pause everything for 1 hour", the last 30 alerts that went to the family with the exact text they saw, and recent changes helpers made. Generated from live config (`core/Transparency.kt`).

### Explicitly out of scope

Message reading, call recording, location tracking, content filtering, browsing history, app time limits, hiding the app, anything on iOS for the parent side.

## 4. Key flows

### Setup (parent present, about 10 minutes)

1. Helper signs up on the web dashboard, creates the family, adds Mom's name, and gets a 6-character setup code and QR.
2. On Mom's phone: install Guardian Mode from Play, open it.
3. Welcome: "Guardian Mode makes your phone simpler and helps your family help you." with the parent's choice: [ Set up with my family ] (no "skip" dark patterns; [ Not now ] exits).
4. Enter or scan the setup code. Shows "Sarah wants to be your helper. [ Yes ] [ No ]".
5. One consent screen per feature group, plain language, each with [ Turn on ] / [ Not now ]:
   a. Simple home screen (set as home app)
   b. Pause before sending money (usage access, display over other apps)
   c. Screen unknown callers (call-screening role, phone state; optional contacts)
   d. Daily "I'm OK" (nothing extra)
   e. Reminders (notifications, exact alarms)
   Each screen states what the family sees and what it never sees, then opens the Android permission screen with a big arrow explaining which switch to tap.
6. Battery: ask for the "unrestricted" battery setting with OEM-specific guidance.
7. Done: "You're all set. Sarah can now help with your phone." Home appears with Sarah's photo tile.

### Helper day-to-day

Dashboard home shows each parent phone as a card: status ("Protected", "Needs attention: screening turned off"), last active, latest alerts. Tabs: Alerts, Home screen, People, Protection, Reminders, Photos, Phone (font, theme, apps), Family (invite helpers, plan).

### An attempted scam (the core story)

1. 10:02 An unknown number calls. Screening silences it; Margaret picks up anyway.
2. 10:05 "Microsoft support" tells her to install AnyDesk. Install → full-screen warning on her phone; Sarah's phone buzzes: "AnyDesk was just installed on Mom's phone during a call with an unknown number."
3. If Margaret continues and the caller asks her to open the bank app: the pause card appears with [ Call Sarah first ].
4. 10:22 Call passes 20 minutes: second urgent alert to Sarah, who calls Mom's phone; call waiting rings because Sarah is family.

## 5. System overview

- **Android app** (Kotlin, Compose): launcher, onboarding, protection service, call screening, reminders, transparency, help.
- **Rules core** (`core/`, pure Kotlin): the tripwire state machine, call correlation, screening policy, reminder schedule, transparency model. 100% unit tested with no Android.
- **Firebase backend**: Auth (helpers: Google / email link; phones: anonymous auth bound to a family on pairing), Firestore (families, devices, config, events, photos metadata), Storage (photos, help screenshots), Cloud Functions (pairing, alert fan-out, check-in evaluator, photo resize, deletion), FCM (wake the phone on config change; push to helper web/PWA).
- **Web dashboard** (React, Vite, TypeScript), installable PWA so iPhone helpers get push notifications (iOS 16.4+ web push for home-screen apps).

## 6. Data model (Firestore)

```
families/{familyId}
  name, plan: "free"|"family", planRenewsAt, createdBy, createdAt
  members/{uid}        role: "owner"|"helper", displayName, email, photoUrl, notify: {urgent: push|push+email, notice: push|digest|off}
  invites/{code}       createdBy, expiresAt, kind: "helper"|"device", deviceId?
  devices/{deviceId}
    parentName, deviceUid (anonymous auth uid), model, androidVersion, appVersion,
    status: {protected, missing: [permissions], lastHeartbeatAt, lastSeenAt}
    installedApps: [{packageName, label}]      (launchable apps only, for the dashboard picker)
    consent: {version, acceptedAt, features: {...}}
    config/current      DeviceConfig JSON (see shared/fixtures/device-config.example.json)
    events/{eventId}    GuardianEvent + {deliveredTo: [...]} (TTL 90 days)
    photos/{photoId}    storagePath, fromUid, fromName, caption, createdAt
    changes/{changeId}  byUid, byName, summary, at   (helper edits, shown to the parent)
```

Security rules: helpers of a family read/write config, photos, invites, and read events; the device's anonymous uid reads its own config/photos and creates events/heartbeats; nobody else reads anything. Server timestamps everywhere; event writes validated against the event-type allowlist so the phone can't send arbitrary data.

## 7. Monetization

- Free: launcher, remote setup, photo card, one helper, screening.
- Family plan ($5.99/mo or $49/yr): all protection alerts, up to 4 helpers, 2 parent phones, reminders with missed-dose alerts, help screenshots.
- Purchased by helpers on the web (Stripe Checkout). The Android app sells nothing, so Play Billing is not involved. Post-MVP: credit-union partner codes.
