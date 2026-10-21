# Testing

Everything runs in the cloud on GitHub Actions. Nothing requires a local machine; Google Antigravity (or Android Studio) on Nate's computer is the fallback for hands-on debugging with a real phone over USB.

## Suites

| Suite | Where | What it proves | Command |
|---|---|---|---|
| Rules core | `core/src/test` | Call correlation, screening, tripwires, long-call, safe word, reminders, transparency: every product rule, deterministic and time-injected | `cd core && ./gradlew test` |
| Android unit + Robolectric | `android/app/src/test` | Home model, feature toggles, card mapping, code parsing | `cd android && ./gradlew testDebugUnitTest` |
| Screenshot (Roborazzi) | `android/app/src/test/.../ScreenshotTest.kt` | Every parent-facing screen at default, 2x text, dark and high contrast, on a 360 dp phone | `./gradlew verifyRoborazziDebug` (record with `recordRoborazziDebug` or the "Record screenshot baselines" workflow) |
| Instrumented | `android/app/src/androidTest` | The app on an emulator with stand-in apps under real package names: real calls through the emulator console, pause cards, lock covers, install alarms, screening, reminders, the notification | `./gradlew connectedDebugAndroidTest` |
| Lint | Android lint | API misuse, accessibility, manifest problems | `./gradlew lintDebug` |
| Backend | `firebase/functions` | Callables, triggers, check-in sweep across time zones, notification copy; Firestore and Storage rules allow/deny for every role | `npm run test:unit`, `npm run test:emulator` |
| Dashboard | `web` | Components, model contract against `shared/fixtures`, event wording; Playwright e2e against the emulator suite | `npm test`, `npm run e2e` |

## Cadence

| Trigger | Workflow | Runs |
|---|---|---|
| Every push and PR | `ci.yml` | core, Android build + unit + screenshots + lint, web, backend |
| Every PR touching Android, every push to `main` | `android-emulator.yml` | Instrumented tests on an API 36 emulator |
| Daily 08:17 UTC | `nightly.yml` ("Daily tests") | Everything above plus tests for known bugs, the on-device suite with stand-in apps on API 29, 33 and 36, host checks (reboot, reinstall, process death, Doze), Playwright e2e, dependency audit, and one report. See `qa/daily/README.md` |
| Daily, after the run | Claude scheduled task | Reads the report, fixes or reports new failures, and automates more cases when all is green |
| Weekly and before release | Firebase Test Lab (set up once the Firebase project exists) | Physical Samsung Galaxy A and Pixel: Robo crawl + instrumented smoke suite |
| Release candidate | all of the above + `docs/RELEASE.md` checklist on two real phones | |

## Install on a real phone

Every push to `main` publishes `guardian-mode-test.apk` to the **testing** pre-release (workflow `test-apk.yml`). It is signed with a fixed test key in `android/testing/`, so a new build installs over the old one and keeps its settings. That key is only for test builds; Play releases will use a separate upload key.

1. On the phone, sign in to GitHub in the browser, open the repo's Releases page, and tap `guardian-mode-test.apk` under "Test build". (Or download it on a computer and copy it over, or `adb install -r guardian-mode-test.apk`.)
2. Allow the browser to install unknown apps when Android asks, then install.
3. Android 13+ blocks some permissions for sideloaded apps. When a switch is greyed out, open Settings > Apps > Guardian Mode debug > ⋮ > "Allow restricted settings", then try again.
4. In the app, choose "Make this phone simpler", then "Set up on my own for now". Family pairing needs the real Firebase project (Blaze plan), so it is off in this build.
5. Accept Guardian Mode as the Home app when asked.
6. To try the lock, pick "Locked" during setup, then open Settings from the pull-down menu. A code screen should cover it within a second. If you forget the test code, uninstall from safe mode (hold the power-off button, then tap and hold "Power off").

This build has no cloud connection: it never contacts the emulator host, and anything it would send to helpers stays queued on the phone.

## Simulating a scam call on an emulator

```bash
adb emu gsm call 5559998888      # incoming call from an unknown number
adb emu gsm accept 5559998888    # answer
adb emu gsm cancel 5559998888    # hang up
adb shell monkey -p com.chase.sig.android 1   # open a watched app (needs it installed)
```
