# Testing

Everything runs in the cloud on GitHub Actions. Nothing requires a local machine; Google Antigravity (or Android Studio) on Nate's computer is the fallback for hands-on debugging with a real phone over USB.

## Suites

| Suite | Where | What it proves | Command |
|---|---|---|---|
| Rules core | `core/src/test` | Call correlation, screening, tripwires, long-call, safe word, reminders, transparency: every product rule, deterministic and time-injected | `cd core && ./gradlew test` |
| Android unit + Robolectric | `android/app/src/test` | Home model, feature toggles, card mapping, code parsing | `cd android && ./gradlew testDebugUnitTest` |
| Screenshot (Roborazzi) | `android/app/src/test/.../ScreenshotTest.kt` | Every parent-facing screen at default, 2x text, dark and high contrast, on a 360 dp phone | `./gradlew verifyRoborazziDebug` (record with `recordRoborazziDebug` or the "Record screenshot baselines" workflow) |
| Instrumented | `android/app/src/androidTest` | The scam story end to end on a real Android build: unknown call → bank app → pause card; family call never arms; remote-access install warning; home tiles | `./gradlew connectedDebugAndroidTest` |
| Lint | Android lint | API misuse, accessibility, manifest problems | `./gradlew lintDebug` |
| Backend | `firebase/functions` | Callables, triggers, check-in sweep across time zones, notification copy; Firestore and Storage rules allow/deny for every role | `npm run test:unit`, `npm run test:emulator` |
| Dashboard | `web` | Components, model contract against `shared/fixtures`, event wording; Playwright e2e against the emulator suite | `npm test`, `npm run e2e` |

## Cadence

| Trigger | Workflow | Runs |
|---|---|---|
| Every push and PR | `ci.yml` | core, Android build + unit + screenshots + lint, web, backend |
| Every PR touching Android, every push to `main` | `android-emulator.yml` | Instrumented tests on an API 36 emulator |
| Nightly 03:00 UTC | `nightly.yml` | Emulator matrix API 29, 33, 36, 37; Playwright e2e; dependency audit |
| Weekly and before release | Firebase Test Lab (set up once the Firebase project exists) | Physical Samsung Galaxy A and Pixel: Robo crawl + instrumented smoke suite |
| Release candidate | all of the above + `docs/RELEASE.md` checklist on two real phones | |

## Testing on a real phone

1. Download `guardian-debug-apk` from the latest CI run's artifacts.
2. Install it (`adb install`, or open the file on the phone).
3. Android 15+ blocks some permissions for sideloaded apps. When a switch is greyed out, open Settings > Apps > Guardian Mode debug > ⋮ > "Allow restricted settings", then try again.
4. Debug builds talk to the Firebase emulators at `10.0.2.2` (an emulator's view of the host). On a real phone, build with `GUARDIAN_USE_EMULATORS=false` and the real Firebase settings once the project exists.

## Simulating a scam call on an emulator

```bash
adb emu gsm call 5559998888      # incoming call from an unknown number
adb emu gsm accept 5559998888    # answer
adb emu gsm cancel 5559998888    # hang up
adb shell monkey -p com.chase.sig.android 1   # open a watched app (needs it installed)
```
