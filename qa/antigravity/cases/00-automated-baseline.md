# 00 · Automated baseline (Track C)

Run the repo's own tests locally first. If any of these fail on a clean `main` checkout, note it at the top of the report; later failures in the same area may be the same problem.

Use the repo root as the working directory. On Windows use `gradlew.bat`.

### GM-BASE-001 · Rules core tests pass  `P0`
**Steps:** `cd core; .\gradlew.bat test`
**Expect:** BUILD SUCCESSFUL. Record the test count from `core/build/test-results`.

### GM-BASE-002 · Android unit and screenshot tests pass  `P0`
**Steps:** `cd android; .\gradlew.bat testDebugUnitTest verifyRoborazziDebug`
**Expect:** success. If screenshot verification fails only on Windows (font rendering differences), say so and attach a diff from `android/app/build/outputs/roborazzi`; CI on Linux is the reference.

### GM-BASE-003 · Android lint is clean  `P1`
**Steps:** `cd android; .\gradlew.bat lintDebug`
**Expect:** no errors. List any new warnings in the report.

### GM-BASE-004 · Instrumented tests pass on each emulator  `P0` `[AVD]`
**Steps:** start one emulator at a time, then `cd android; .\gradlew.bat connectedDebugAndroidTest`. Repeat on `gm-api29-budget`, `gm-api33`, `gm-api36`.
**Expect:** all four tests in `ProtectionFlowTest` pass on each. Attach `android/app/build/reports/androidTests/connected/index.html` for any failure, with `adb logcat -d` filtered to `UiDump|GuardianBrain|CardLauncher|AndroidRuntime`.
**Note:** this installs a debug build signed with your local key. Uninstall it afterwards on emulators you'll use for Track A.

### GM-BASE-005 · Backend unit and emulator tests pass  `P1`
**Steps:** `cd firebase\functions; npm ci; npm run lint; npm run test:unit; npm run test:emulator`
**Expect:** all pass. `test:emulator` needs Java 21 on the path.

### GM-BASE-006 · Dashboard tests and end-to-end pass  `P1`
**Steps:** `cd web; npm ci; npm run typecheck; npm run lint; npm test`. Then with the emulators running (RUNBOOK §8 step 1): `npm run e2e`.
**Expect:** all pass.

### GM-BASE-007 · The stand-in apps build  `P0`
**Steps:** `cd qa\antigravity\stub-apps; .\gradlew.bat assembleDebug`
**Expect:** eleven APKs under `app\build\outputs\apk\`. Check one with `aapt2 dump badging` (in `Sdk\build-tools\<version>`) to confirm the package name.

### GM-BASE-008 · The published test APK matches main  `P1`
**Steps:** open the `testing` release page. Note the commit in the title and compare with `git log -1 origin/main`. `aapt2 dump badging guardian-mode-test.apk` for package, version and target SDK.
**Expect:** package `app.guardianmode.debug`, `targetSdkVersion` 36, `minSdkVersion` 29, built from the latest `main` commit. If the release is missing, a draft, or behind `main`, report it (there's an open fix for the release step; just note what you see).

### GM-BASE-009 · Manifest permission audit  `P0`
**Steps:** `aapt2 dump permissions guardian-mode-test.apk` and `aapt2 dump xmltree --file AndroidManifest.xml guardian-mode-test.apk`.
**Expect:**
- No `READ_SMS`, `RECEIVE_SMS`, `SEND_SMS`, `READ_CALL_LOG`, `WRITE_CALL_LOG`, `PROCESS_OUTGOING_CALLS`, `QUERY_ALL_PACKAGES`, `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `RECORD_AUDIO`, `BIND_ACCESSIBILITY_SERVICE`, `READ_MEDIA_IMAGES`, `READ_EXTERNAL_STORAGE`, `USE_EXACT_ALARM`.
- No `<service>` with the `android.accessibilityservice.AccessibilityService` action in Guardian's own manifest (the `<queries>` entry for it is fine: that's how it spots other apps that declare one).
- No `isMonitoringTool` meta-data.
- `allowBackup="false"`.
- Every permission present maps to a feature in `docs/POLICY.md` §2. List any that don't.
- Exported components are only: `MainActivity`, `SharePhotosActivity`, `GuardianCallScreeningService` (guarded by `BIND_SCREENING_SERVICE`), `PhoneStateReceiver` (guarded by `READ_PHONE_STATE`), `BootReceiver`. Anything else exported is a bug.
