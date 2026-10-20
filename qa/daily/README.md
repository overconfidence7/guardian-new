# Daily test suite

Every morning GitHub Actions runs every automated test in the repo, on GitHub's machines, and writes one report. Nothing runs on Nate's computer or phone. A Claude scheduled task reads the report afterwards and acts on it.

It replaces the hands-on Antigravity pass: the 308 cases in `qa/antigravity/cases/` are the catalog, and each one is either automated (a test carries its id), covered by a cloud stand-in because it truly needs a person or a real phone (`catalog.json`), or not automated yet. The report counts all three, so coverage only goes up.

## What runs

Workflow `.github/workflows/nightly.yml` ("Daily tests"), 08:17 UTC, or by hand from the Actions tab.

| Job | What | Where the tests live |
|---|---|---|
| Rules core | Every product rule with an injected clock: tripwires, call tracking, screening, lock policy, reminders, transparency | `core/src/test` |
| Android unit | Compose screens under Robolectric, card and consent wording, banned words, accessibility targets, screenshot baselines, lint, the stand-in apps build | `android/app/src/test` |
| Device (API 29, 33, 36) | The app on real Android emulators with the stand-in apps installed: real incoming calls through the emulator console, real app switches seen through Usage access, cards found on screen, lock covers, install alarms, the notification, reminders | `android/app/src/androidTest` |
| Device host checks (API 29, 36) | Things that need adb from outside the emulator: reboot, reinstall over the top, Android killing the process after a call, Doze, memory, the manifest audit | `qa/daily/device_checks.py` |
| Backend | Firestore and Storage rules for every role, callables, triggers, the check-in sweep, on the Firebase emulator | `firebase/functions/test` |
| Dashboard | Unit tests, typecheck, lint, build, and Playwright end to end against the Firebase emulator, playing the phone through the same API it uses | `web/src`, `web/e2e` |
| Audit | `npm audit` for the dashboard and functions | |
| Report | Joins everything into `REPORT.md` and `results.json`, and updates the "Daily test report" issue | `qa/daily/report.py`, `publish_issue.py` |

The report says what newly failed, which known bugs are still open (or now pass), which suites produced nothing (usually a compile error), coverage per area, the P0 cases still not automated, and measurements (APK size, memory, reboot-to-protection time).

## The daily check

A Claude scheduled task runs after the workflow each day. It reads the issue (or the run, if the issue wasn't updated), and:

1. **New failure in a test or workflow**: finds the cause, fixes it, opens a PR.
2. **New failure that's a real app bug**: opens a PR with the fix and a test, or, if it's a product decision, tells Nate in the project.
3. **A known bug now passes**: opens a PR removing its known-bug marker and `known-bugs.json` entry.
4. **All green**: automates the next few P0/P1 cases from "not automated yet" and opens a PR.

Its PRs wait for Nate's "merge" like everything else.

## Conventions (keep the suite growing)

- **Every new feature or bug fix adds or updates a case** in `qa/antigravity/cases/` (next free id in the right file, with priority and steps) **and a test that carries the id**. Pick the lowest layer that proves it: core rule test, then JVM/Compose test, then on-device test.
- **Test names carry case ids.** Kotlin: `fun gm_pause_004_cardAt29MinutesNotAt31()`. JS/TS: `it('GM-FAM-004 rejects wrong, expired and reused codes', ...)`. A test can carry several ids.
- **Known bugs**: write the test for the right behavior anyway, mark it, and add an entry to `known-bugs.json`. Pull-request CI skips marked tests; the daily run includes them.
  - core (JUnit 5): `@Tag("known-bug")`
  - Android unit (JUnit 4): `@Category(KnownBug::class)`
  - Android device: `@KnownBug("S-03")` from `support/`
  - vitest / Playwright: `it.skipIf(!process.env.INCLUDE_KNOWN_BUGS)(...)`, with "[known bug S-17]" in the title
  - Gradle: `-PincludeKnownBugs=true` runs them locally.
- **Device tests** use `support/DeviceRule` (fast setup, state reset, UI dump on failure), `EmulatorConsole` for real calls, and `StubApp` for the stand-ins. Stand-ins for remote-control apps aren't pre-installed; tests install them from `/data/local/tmp/stubs/<flavor>.apk`.

## Running pieces locally

```bash
cd core && ./gradlew test -PincludeKnownBugs=true
cd android && ./gradlew testDebugUnitTest -PincludeKnownBugs=true
cd android && ./gradlew connectedDebugAndroidTest   # with an emulator running and the stand-ins installed
python3 qa/daily/report.py --results <folder of JUnit XML> --out /tmp/report
```

## What still needs a person

`catalog.json` lists the few cases a cloud emulator can't do for real (battery over 8 hours on real hardware, hidden caller ID, carrier caller-ID verification, a TalkBack walk-through, the Play Store's restricted settings) and what covers each one in the cloud instead.
