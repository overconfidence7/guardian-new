# Build plan

Companion to `SPEC.md` (what) and `POLICY.md` (the rules). This is how, in what order, and how we know each step is done.

## 1. Key decisions

| Decision | Choice | Why |
|---|---|---|
| Android stack | Kotlin 2.4.20, Jetpack Compose (BOM 2026.09.00), AGP 9.4.0, Gradle 9.8.1 | Current stable; Compose makes big-type, high-contrast UI and screenshot testing straightforward. |
| SDK levels | minSdk 29, targetSdk 36, compileSdk 37 | Call screening needs Android 10; Play requires target 36 since 2026-08-31. Android 10 still covers most hand-me-down phones. |
| Application id | `app.guardianmode` | Permanent once published. Change before the first Play upload if Nate owns a different domain. |
| Architecture | Single `:app` module + pure-Kotlin `core` composite build; manual DI (`AppContainer`); StateFlow + Compose; DataStore for local state | The risky logic lives in `core` where it is fast to test. The app is small enough that Hilt/multi-module would cost more than it saves. |
| Backend | Firebase: Auth, Firestore, Storage, Cloud Functions (TypeScript, Node 22), FCM, Hosting | One vendor for Android + web push + realtime config; the Emulator Suite lets CI test rules and functions offline; generous free tier. |
| Web dashboard | React 19 + Vite + TypeScript, installable PWA, plain CSS with design tokens | Works on iPhone (web push for home-screen PWAs on iOS 16.4+), no app-store review for helpers. |
| Payments | Stripe Checkout on the web, entitlement stored on the family | Helpers are the buyers; the Android app sells nothing, so no Play Billing. |
| Monitoring posture | Self-protection with trusted helpers; discrete events only; no `isMonitoringTool` flag by default (toggle in `gradle.properties`) | See `POLICY.md` §1. Decision confirmed by Nate before the first Play submission. |

## 2. Repository layout

```
core/                 Pure Kotlin rules engine (Gradle composite build), JVM unit tests
android/              Android app (Gradle), includes ../core
  app/src/main/...    launcher, onboarding, service, screening, reminders, transparency, help
  app/src/test/...    Robolectric + Roborazzi screenshot tests, ViewModel tests
  app/src/androidTest Instrumented tests run on emulators in CI
web/                  Helper dashboard (Vite + React + TS), Vitest, Playwright e2e
firebase/             firestore.rules, storage.rules, indexes, functions/ (TS) + tests
shared/fixtures/      JSON contract fixtures used by Kotlin and TS tests
docs/                 SPEC, POLICY, BUILD_PLAN, TESTING, RELEASE
.github/workflows/    CI pipelines (see §5)
```

## 3. Milestones

Each milestone ends with: CI green, the acceptance checks below passing, and the docs updated.

### M0. Foundations
- Repo, CI skeleton, `core` with call correlation, screening policy, tripwire engine, reminder schedule, transparency model (**done: 41 unit tests**).
- Accept: `core` tests run on every push in under 2 minutes.

### M1. Launcher shell
- Compose theme for older eyes (type scale ×1.3 default, 64 dp targets, AA/AAA contrast palettes, light/dark/high-contrast).
- Home screen: clock, photo card placeholder, tile grid from `DeviceConfig`, "All my apps", shield chip. Calls go through `ACTION_CALL` with `CALL_PHONE` or `ACTION_DIAL` fallback.
- Local config cache with a built-in default so the phone works offline from first launch.
- Accept: Roborazzi screenshots for home in light/dark/high-contrast at font scales 1.0/1.3/2.0 on small (360 dp) and large phones; TalkBack labels test; instrumented test sets Guardian as HOME and launches a tile.

### M2. Protection on the phone
- `GuardianService` (FGS `specialUse`, persistent "Guardian Mode is on" notification with "Help" and "Pause protection" actions).
- `GuardianCallScreeningService` → `CallScreeningPolicy`; `TelephonyCallback` → `CallTracker` → `TripwireEngine`.
- Foreground watcher: UsageStats `queryEvents` every 1 s while armed and screen on, every 5 s otherwise while screen on, stopped when screen off.
- Install watcher: runtime `PACKAGE_ADDED` + `getChangedPackages` on unlock.
- Pause, remote-access and safe-word activities (full screen, show-when-locked off, one-tap call).
- Event outbox (DataStore) that survives offline and process death.
- Accept: emulator test drives `adb emu gsm call` → answer → launch a fake "bank" test APK → pause card visible within 2 s; family call never arms; Robolectric tests for each card; manual test on one Samsung and one Motorola.

### M3. Onboarding and consent
- Welcome, code entry / QR scan (CameraX + ML Kit barcode, offline), helper confirmation, per-feature consent screens, permission deep links with return detection, battery optimization, done.
- Transparency screen with per-feature switches, pause-all, alert history, recent changes, remove helper, stop using Guardian.
- Accept: instrumented test walks the full onboarding on API 29 and 36 emulators with permissions granted by `adb shell appops` / `cmd role`; screenshot tests for every onboarding page at font scale 2.0.

### M4. Backend
- Firestore model and security rules per `SPEC.md` §6, with rules unit tests for every allow/deny pair.
- Functions: `createFamily`, `createInvite`, `claimDeviceInvite` (binds the anonymous uid), `acceptHelperInvite`, `onEventCreated` (fan-out push + email), `checkInSweep` (every 15 min), `onPhotoUploaded` (resize), `onConfigWritten` (bump revision, FCM data ping to the device, change log), `deleteFamily` / `deleteDevice`.
- Accept: functions and rules tests green in the Emulator Suite in CI.

### M5. Phone ↔ cloud
- Anonymous auth, pairing, Firestore listener on `config/current` while running + FCM data wake, heartbeat writer, event upload from outbox, photo download/cache (Coil), installed-apps report, help screenshot upload.
- Accept: emulator test against the Firebase emulators: config written from a test script appears on the phone within 10 s; an event created on the phone appears in Firestore; airplane-mode test for the outbox.

### M6. Helper dashboard
- Sign in, create family, invite, pair phone (code + QR), device overview, alerts feed (realtime), home-screen editor (drag tiles, live phone preview), people & photos, protection settings, reminders, phone display settings, family & plan, account deletion. PWA install + web push.
- Accept: Vitest component tests; Playwright e2e against emulators covering sign-up → invite → (simulated) phone claim → edit config → see event; Lighthouse accessibility ≥ 95; works at 375 px wide.

### M7. Everyday care
- Reminders (exact alarms with fallback, full-screen card, missed-dose notice), check-in heartbeat + server sweep, Help screenshot flow.
- Accept: Robolectric alarm scheduling tests; functions test for the sweep across time zones and DST; emulator test for MediaProjection consent path.

### M8. Polish and release readiness
- Motion and haptics review, copy review against the calm-voice rules, accessibility audit (TalkBack, Switch Access, 200% font), battery measurement (Battery Historian on a real device, < 2%/day), crash reporting (Firebase Crashlytics), privacy policy and terms, store listing, Data safety form, FGS declaration video, closed testing track.
- Accept: release checklist in `docs/RELEASE.md` all ticked.

### Post-MVP
Live screen share (WebRTC, approved each time), Stripe billing, credit-union partner codes, multi-language (Spanish first), wear/tablet layouts.

## 4. What only Nate can provide

| Item | Needed by | Notes |
|---|---|---|
| GitHub repo `overconfidence7/guardian-mode` with the Claude app installed | Now | Blocks pushing and all cloud CI. |
| Firebase project on the Blaze (pay-as-you-go) plan | M4–M5 | Scheduled functions require Blaze. Expected cost at MVP scale: near $0; set a $10 budget alert. Then add a CI service-account key as the `FIREBASE_SERVICE_ACCOUNT` secret and download `google-services.json`. |
| Google Play Console developer account | M8 | $25 one-time. A *personal* account created after Nov 2023 must run a closed test with 12+ testers for 14 days before production; an *organization* account (needs a D-U-N-S number) skips that. |
| Upload signing key | M8 | I can generate one in CI; Nate stores the keystore and passwords as GitHub secrets and a backup offline. Play App Signing holds the real key. |
| Domain and support email | M8 | Privacy policy URL, dashboard hosting, invite emails. |
| Decision on the monitoring-tool flag and a Play policy pre-review | Before first submission | See `POLICY.md` §1. |
| Stripe account | Post-MVP | Billing. |
| Two real test phones (ideally an old Samsung Galaxy A and a Motorola) | M2 onward | OEM battery and permission behavior can't be emulated. Firebase Test Lab covers some of it. |
| Trademark check on "Guardian Mode" | Before launch | Generic name; check USPTO and Play for conflicts. |

## 5. Automated testing cadence

Everything runs in the cloud on GitHub Actions; nothing needs a local machine. Google Antigravity on Nate's machine is the fallback for hands-on debugging with a real phone over USB.

| When | What | Where | Time budget |
|---|---|---|---|
| Every push and PR | `core` unit tests; Android lint, unit + Robolectric tests, Roborazzi screenshot **verify**, debug APK build; web typecheck, lint, Vitest, production build; functions build + tests and Firestore/Storage rules tests in the Firebase Emulator Suite | `ubuntu-latest` | < 12 min, parallel jobs |
| Every PR to `main` and every push to `main` | Instrumented tests on an API 36 emulator (`reactivecircus/android-emulator-runner@v2`, KVM, `google_apis` x86_64) | `ubuntu-latest` | < 20 min |
| Nightly (03:00 UTC) | Emulator matrix API 29, 33, 36, 37; Playwright e2e of the dashboard against emulators; dependency and vulnerability audit (`npm audit`, Gradle dependency check) | `ubuntu-latest` | ~60 min |
| Weekly (Sunday) + before each release | Firebase Test Lab on physical devices (Spark free tier: 5 physical runs/day): a Samsung Galaxy A-series and a Pixel, Robo crawl + instrumented smoke suite | Test Lab | ~30 min |
| Every release candidate | Everything above plus the manual checklist in `docs/RELEASE.md` on two real phones | Nate + CI | — |

Screenshot baselines are recorded with `./gradlew recordRoborazziDebug` (a manual workflow, `record-screenshots.yml`, commits them) and verified on every push, so any visual change shows up as a diff in the PR.

Cost note: GitHub's free plan includes a monthly Actions-minutes allowance for private repos; the nightly emulator matrix is the biggest consumer. If minutes run short, drop the nightly matrix to API 29 + 36 or run it three times a week.
