# Runbook

Read this whole file before running anything. It is written to the agent doing the testing.

## 1. Your job

Test the Guardian Mode Android app as thoroughly as you can, the way a careful QA engineer and a determined scammer would. Run every case in `cases/`, record a result for each, and write up every problem with enough detail that a developer can fix it without asking you anything. Then go past the script: explore, try to break things, and add the cases you wish had been written.

The person this app is for is a 75-year-old on a hand-me-down phone. The person trying to beat it is a scammer on the phone with them, telling them exactly what to tap. Keep both in mind on every screen.

## 2. Rules

1. **Don't change app code.** You are testing, not fixing. If something blocks a whole track (for example the debug build can't reach the local emulators), you may apply the smallest local patch needed to keep testing, but never commit or push it, and record it in the report as a bug with the patch attached.
2. **Git:** work on a new local branch `qa/antigravity-run-YYYY-MM-DD` from `main`. Don't push to `main`, and don't touch any other branch or open pull request. Results go in `qa/antigravity/results/RUN-YYYY-MM-DD/`. Screenshots and recordings go in its `evidence/` folder, which git ignores. Ask Nate before pushing anything.
3. **The real test phone is Nate's.** Before anything that wipes Guardian's settings on it (uninstall, clear data, "Stop using Guardian Mode"), or installs anything on it, ask Nate first. Never factory reset it. Never install a stand-in app on a phone that has the real app with that package name.
4. **Phone calls:** on emulators, use the emulator's fake calls (`adb emu gsm ...`). On the real phone, never call or text anyone except numbers Nate gives you for testing. Any "Call Sarah" button on the real phone must dial a number Nate approved.
5. **No real remote-control apps connected to anyone.** Use the stand-in apps in `stub-apps/`. If you want to try a real AnyDesk or TeamViewer install, do it only on a Play Store emulator, never accept a connection, and uninstall it afterwards.
6. **No real accounts.** Track B uses the Firebase emulators and the dashboard's "Developer sign-in (emulators only)". Don't sign in to Google, Firebase or GitHub on Nate's behalf, and don't touch the real Firebase project `guardian-mode-c45a7`.
7. **Don't weaken Nate's machine.** No turning off Windows Defender, the firewall, or anything else to make a test pass.
8. **Keep going.** One failure doesn't stop the run. Mark it, write it up, move on. The exception is a P0 failure: write it up first, tell Nate in one line, then carry on.
9. **Batch the questions.** Collect every `[HUMAN]` step and every question for Nate, and ask in as few interruptions as you can, with exact instructions ("Call the test phone from your own phone now and hang up after 40 seconds").
10. **Evidence for everything that fails.** A screenshot or screen recording, the UI dump, and the relevant logcat lines. For passes, a screenshot is enough on the first run of each case.

## 3. Ground truth

The spec decides what is right: `docs/SPEC.md` (features and the "what good looks like" bars), `docs/POLICY.md` (Play policy and permissions), `docs/SECURITY.md` (threat model). Read all three before starting. The cases quote the exact wording the app should show; if the screen differs, check the code (`android/app/src/main/kotlin/...`) to see whether the case or the app is out of date, and report which.

`SUSPECTS.md` lists problems found by reading the code. Confirm or rule out every one, with evidence.

## 4. The machine

Nate's Windows PC. His Android projects folder is `C:\Users\nkurt\AndroidStudioProjects`; clone or update the repo there (`overconfidence7/guardian-new`).

Check these before starting and install what's missing (ask Nate before installing anything system-wide):

| Tool | Check | Notes |
|---|---|---|
| Android SDK | `%LOCALAPPDATA%\Android\Sdk` | Comes with Android Studio. Needs platform-tools, emulator, cmdline-tools, platform 36 |
| JDK 21 | `java -version` | Android Studio's bundled one works: `C:\Program Files\Android\Android Studio\jbr`. Set `JAVA_HOME` to it for the session |
| Node 22 + npm | `node -v` | Track B and C (dashboard, Firebase functions) |
| Python 3 | `python --version` | For small scripts: editing state JSON, checking dumps |
| Git | `git --version` | |
| Hardware acceleration | `emulator -accel-check` | Emulators need WHPX or AEHD. If it's off, tell Nate; don't change BIOS or Windows features yourself |

In PowerShell, put adb and the emulator on the path for the session:

```powershell
$sdk = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$sdk\platform-tools;$sdk\emulator;$sdk\cmdline-tools\latest\bin;$env:Path"
```

## 5. Devices

Create these emulators (`sdkmanager` for the images, `avdmanager` to create, or Android Studio's Device Manager):

| Name | Image | Why |
|---|---|---|
| `gm-api29-budget` | `system-images;android-29;google_apis;x86_64` | The spec's target: Android 10, 3 GB RAM, small screen. Set `hw.ramSize=3072`, 720x1520 at 320 dpi in the AVD's `config.ini` |
| `gm-api33` | `system-images;android-33;google_apis;x86_64` | Android 13: notification permission, restricted settings start |
| `gm-api36` | `system-images;android-36;google_apis;x86_64` | Current target SDK. Your main device. Rootable (`adb root`), so clock changes work |
| `gm-api36-play` | `system-images;android-36;google_apis_playstore;x86_64` | Behaves like a real consumer phone: sideload restrictions, "Allow restricted settings", no root |

Plus the **real test phone** over USB (Nate already has the testing build on it in solo mode). Ask Nate to plug it in and allow USB debugging when you reach the `[PHONE]` cases. Record its make, model and Android version in the report.

Start an emulator with a writable system and no snapshot so each run starts clean:

```powershell
emulator -avd gm-api36 -no-snapshot-load
```

Run every P0 case on at least `gm-api29-budget` and `gm-api36`, and on the real phone where tagged. P1 and P2 cases run on `gm-api36` unless the case says otherwise.

## 6. Builds

### Track A: the test APK

Download `guardian-mode-test.apk` from https://github.com/overconfidence7/guardian-new/releases/tag/testing (it's public to Nate's GitHub account; use `gh release download testing -R overconfidence7/guardian-new` if `gh` is signed in, or ask Nate to download it). Note the commit in the release title. This build has no cloud connection; family linking is off.

```powershell
adb install -r guardian-mode-test.apk
```

If the release is missing or is a draft, build the same thing yourself from the checkout:

```powershell
cd android
$env:GUARDIAN_USE_EMULATORS = "false"
.\gradlew.bat assembleDebug
# APK: android\app\build\outputs\apk\debug\app-debug.apk
```

The package name is **`app.guardianmode.debug`** in both cases.

### Track B: debug build talking to the local emulators

```powershell
cd android
Remove-Item Env:GUARDIAN_USE_EMULATORS -ErrorAction SilentlyContinue   # default is true for debug
.\gradlew.bat assembleDebug
```

This build talks to the Firebase emulators at `10.0.2.2` (the host PC as seen from an Android emulator). Use it on emulators only. It is signed with your local debug key, so if the Track A APK is installed on the same emulator, uninstall it first (`adb uninstall app.guardianmode.debug`). Never do that on the real phone without asking.

See `SUSPECTS.md` S-01: Android blocks plain `http://` traffic by default, and the app may not have a debug network security config, so pairing may fail with a cleartext error in logcat. If it does, that's a bug to report, and the local patch in S-01 is the one exception to rule 1.

### Stand-in apps

```powershell
cd qa\antigravity\stub-apps
.\gradlew.bat assembleDebug
# APKs: app\build\outputs\apk\<name>\debug\app-<name>-debug.apk
```

| Flavor | Package | Label | Counts as |
|---|---|---|---|
| chase | `com.chase.sig.android` | Chase | bank |
| venmo | `com.venmo` | Venmo | payment |
| zelle | `com.zellepay.zelle` | Zelle | payment |
| target | `com.target.ui` | Target | gift card |
| coinbase | `com.coinbase.android` | Coinbase | crypto |
| anydesk | `com.anydesk.anydeskandroid` | AnyDesk | remote control |
| teamviewer | `com.teamviewer.quicksupport.market` | TeamViewer QuickSupport | remote control |
| rustdesk | `com.carriez.flutter_hbb` | RustDesk | remote control |
| creditunion | `org.guardianqa.hometowncu` | Hometown CU | nothing until the family adds it (Track B) |
| notes | `org.guardianqa.notes` | Notes | an ordinary app, for lock and app-list tests |
| screenhelper | `org.guardianqa.screenhelper` | Screen Helper | not on any list, but declares an accessibility service, so it "can control the screen" |

Each one opens a screen that says which app it stands in for, and does nothing else. Open one with `adb shell monkey -p <package> -c android.intent.category.LAUNCHER 1`.

## 7. adb cheat sheet

`$P = "app.guardianmode.debug"` below.

**Grant everything without tapping through Settings** (use only after the consent cases have been run by hand once per device; label results that used this "fast setup"):

```powershell
adb shell pm grant $P android.permission.READ_PHONE_STATE
adb shell pm grant $P android.permission.CALL_PHONE
adb shell pm grant $P android.permission.READ_CONTACTS
adb shell pm grant $P android.permission.POST_NOTIFICATIONS
adb shell appops set $P GET_USAGE_STATS allow
adb shell appops set $P SYSTEM_ALERT_WINDOW allow
adb shell appops set $P SCHEDULE_EXACT_ALARM allow
adb shell appops set $P USE_FULL_SCREEN_INTENT allow
adb shell cmd role add-role-holder android.app.role.HOME $P
adb shell cmd role add-role-holder android.app.role.CALL_SCREENING $P
adb shell dumpsys deviceidle whitelist +$P
```

Revoke one to test what happens when it goes missing: `pm revoke`, `appops set ... deny` (or `default`), `cmd role remove-role-holder`.

**Fake calls (emulator only):**

```powershell
adb emu gsm call 5559998888     # unknown number rings in
adb emu gsm accept 5559998888   # answer it
adb emu gsm cancel 5559998888   # hang up
adb emu gsm list                # what's going on
adb shell am start -a android.intent.action.CALL -d tel:5550101111   # the phone calls out
```

Use different numbers for "unknown" (`555999xxxx`) and for numbers you put in the phone's contacts or Guardian's family list.

**Add a phone contact:** open the Contacts app and save one (it's quick), or `adb shell am start -a android.intent.action.INSERT -t vnd.android.cursor.dir/contact -e name "Dr Patel" -e phone 5550103333` and tap Save.

**Install / remove a stand-in:** `adb install -r <apk>`, `adb uninstall <package>`.

**What's on screen:**

```powershell
adb shell screencap -p /sdcard/s.png; adb pull /sdcard/s.png evidence\GM-XXXX-NNN.png
adb shell screenrecord /sdcard/clip.mp4   # Ctrl+C to stop, then adb pull /sdcard/clip.mp4
adb shell uiautomator dump /sdcard/ui.xml; adb pull /sdcard/ui.xml evidence\GM-XXXX-NNN.xml
adb shell dumpsys activity activities | Select-String "topResumedActivity|mResumedActivity"
```

**Logs:** `adb logcat -v time GuardianBrain:V CardLauncher:V GuardianScreening:V CloudSync:V ScreenCapture:V PhotoImporter:V AndroidRuntime:E *:S`. Clear first with `adb logcat -c`. Always also grab `AndroidRuntime` for crashes.

**Guardian's saved state.** The test build is debuggable, so you can read the phone's whole local state, which is the best way to check what was recorded and what is waiting to be sent:

```powershell
adb shell "run-as $P cat files/datastore/guardian_state.json > /data/local/tmp/gm-state.json"
adb pull /data/local/tmp/gm-state.json evidence\state.json
```

(Pull files with `adb pull` rather than redirecting `adb shell` output in PowerShell: Windows PowerShell 5 rewrites redirected output as UTF-16, which breaks JSON and PNGs. The same goes for screenshots: `adb shell screencap -p /sdcard/s.png` then `adb pull`.)

Fields worth knowing: `onboardingDone`, `consent`, `config` (the DeviceConfig), `pairing`, `outbox` (events waiting to upload), `sharedEvents` (what the transparency screen lists), `pausedUntil`, `lockCodeHash`, `lockFailures`, `lockRetryAt`, `unlockedUntil`, `localPhotos`.

**Changing the saved state** (for example to add family members, a safe word or reminders in Track A, where there's no dashboard). Force-stop first, edit, push back, relaunch:

```powershell
adb shell am force-stop $P
adb shell "run-as $P cat files/datastore/guardian_state.json > /data/local/tmp/gm-state.json"
adb pull /data/local/tmp/gm-state.json state.json
# edit state.json with Python (keep it valid JSON, UTF-8 without a BOM)
adb push state.json /data/local/tmp/gm-state.json
adb shell "run-as $P sh -c 'cat /data/local/tmp/gm-state.json > files/datastore/guardian_state.json'"
adb shell rm /data/local/tmp/gm-state.json
adb shell monkey -p $P -c android.intent.category.HOME 1
```

`templates/state-family.json` has a ready `config` block (Margaret, daughter Sarah, son David, a contact, safe word "bluebird", two reminders, an allowed Notes app). Merge it into the pulled state's `config`. Results that relied on injected state must say so. Never inject real phone numbers on the real phone except ones Nate gave you.

**Clock (rooted emulators only):** `adb root`, `adb shell settings put global auto_time 0`, then `adb shell date MMDDhhmmYYYY.ss` (for example `adb shell date 102014302026.00`). Set `auto_time` back to 1 when done. Moving the clock is how you test the 30-minute window, the 10-minute unlock, the 1-hour pause and reminder times without waiting; run each boundary for real once on `gm-api36` too.

**Time zone:** Settings > System > Date & time (turn off automatic time zone first), or on a rooted emulator `adb shell setprop persist.sys.timezone America/Los_Angeles` followed by a reboot.

**Font and display size:** `adb shell settings put system font_scale 2.0`, `adb shell wm density 400` (`wm density reset` to undo).

**Dark mode:** `adb shell cmd uimode night yes` / `no`.

**Process death:** `adb shell am kill $P` (background only) or `adb shell am force-stop $P` (stronger; also cancels alarms, so note which you used).

**Doze:** `adb shell dumpsys deviceidle force-idle`, `adb shell dumpsys deviceidle unforce`.

**Reset to a fresh install:** `adb shell pm clear $P` (emulators; ask first on the real phone).

## 8. Track B: starting the local stack

Three things run on the PC, each in its own terminal.

1. **Firebase emulators** (project `demo-guardian`, no real project, no login):
   ```powershell
   cd firebase\functions
   npm ci
   npm run emulators
   ```
   Auth 9099, Firestore 8080, Storage 9199, Functions 5001, UI at http://127.0.0.1:4000 (use the UI to look at every document the phone writes).
2. **Web dashboard** against the emulators:
   ```powershell
   cd web
   npm ci
   $env:VITE_USE_EMULATORS = "true"
   npx vite --port 5173 --host 127.0.0.1
   ```
   Open http://127.0.0.1:5173 in a browser you can drive. Sign in with "Developer sign-in (emulators only)", for example `sarah@example.com` / `password123`. Use a second browser profile for a second helper (`david@example.com`).
3. **An emulator** with the Track B debug build installed.

Helper notifications in the emulator go to a fake messenger that logs instead of sending (see `firebase/README.md`). Read helper-facing wording from the Functions emulator log and from the dashboard's Alerts tab. The scheduled check-in sweep doesn't run in the emulator; see GM-FAM cases for how to cover it.

## 9. Recording results

Copy `templates/` into `qa/antigravity/results/RUN-YYYY-MM-DD/` and fill in:

- **`results.csv`**: one row per case per device: `id, device, build, result, evidence, bug, notes`. Results are `PASS`, `FAIL`, `BLOCKED` (couldn't run; say why), `SKIP` (deliberately not run; say why) or `N/A`.
- **`REPORT.md`**: summary at the top (counts by result and priority, the P0s in one line each, what you couldn't test), then every bug, then the confirmed or ruled-out suspects, then the new cases you added and ran, then recommendations.

Each bug:

```
### BUG-007 · Pause card doesn't appear for Zelle during a call   P0
Device: gm-api36 (Android 16), build 3f2a1c9 (testing release #41)
Case: GM-PAUSE-004
Steps:
  1. ...
Expected: ... (quote the spec line)
Actual: ...
Evidence: evidence/GM-PAUSE-004-api36.mp4, evidence/GM-PAUSE-004-logcat.txt
Repeats: 3 of 3 tries
Likely cause: android/app/src/main/kotlin/app/guardianmode/... (line), and why you think so
Suggested fix: ...
```

Priorities for bugs follow the case priorities in the README. Raise a bug's priority if it's worse than the case suggested (for example a P2 wording case that turns out to tell the parent something false).

## 10. Order of work

1. Track C baseline (`cases/00`).
2. Track A by hand on `gm-api36`: install, setup and consent with real permission screens (01, 02).
3. Track A core protection on `gm-api36` and `gm-api29-budget`: lock (03), pause cards (06), remote-control alarm (07), screening (08), long calls and safe word (09).
4. The rest of Track A: home (04), photos (05), transparency (10), service (11), help (12), privacy (14), accessibility (15), robustness (16), performance (17), wording (18).
5. Track A on `gm-api33` and `gm-api36-play`: the P0 cases, plus consent on the Play image (restricted settings).
6. The real phone: every `[PHONE]` case, with Nate for `[HUMAN]` steps.
7. Track B (13), then the cases in other files tagged `[B]`.
8. Scam scenarios (19) end to end, on the real phone where possible.
9. Exploratory time: at least an hour of unscripted breaking. Write each new finding up as a case (`GM-NEW-001`...) in the report.
10. Write the report.

At each step boundary, update `REPORT.md` so a stopped run still leaves something useful.
