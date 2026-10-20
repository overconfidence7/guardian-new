# 17 · Performance and battery

`docs/SPEC.md` bar 7: works on a 4-year-old budget phone (Android 10, 3 GB RAM) with under 2% battery a day attributable to Guardian. Bars 1–3 set the timing targets: call family within 5 s of picking up the phone, dashboard changes within 10 s, pause card within 2 s.

Run timing cases on `gm-api29-budget` and the real phone. Report min / median / max over 10 runs.

### GM-PERF-001 · Cold start of the home screen  `P1`
**Steps:** `adb shell am force-stop app.guardianmode.debug`, then `adb shell am start -W -n app.guardianmode.debug/app.guardianmode.ui.MainActivity` ten times.
**Expect:** TotalTime under 1.5 s on the budget emulator. The first frame shows the real home screen, not a blank or a loading state (the launcher reads its state at start-up on purpose).

### GM-PERF-002 · Home after pressing Home from another app  `P1`
**Expect:** Guardian's home is back in under 300 ms (it's the launcher; measure from a screen recording).

### GM-PERF-003 · Pause card latency  `P0`
Covered by GM-PAUSE-040. Add the real phone.

### GM-PERF-004 · Locked-app cover latency  `P1`
Covered by GM-LOCK-030. Add the real phone.

### GM-PERF-005 · Remote-control install warning latency  `P1`
Covered by GM-RACC-001. Add the real phone.

### GM-PERF-006 · Jank  `P2`
**Steps:** `adb shell dumpsys gfxinfo app.guardianmode.debug reset`, scroll the home screen and the transparency screen up and down 20 times, then `dumpsys gfxinfo app.guardianmode.debug`.
**Expect:** janky frames under 5%. Report the numbers.

### GM-PERF-007 · Memory  `P1`
**Steps:** `adb shell dumpsys meminfo app.guardianmode.debug` on home, with 30 photos, and after an hour idle.
**Expect:** report PSS. Flag anything over 150 MB on the budget emulator, and any growth over the hour (leak).

### GM-PERF-008 · APK size  `P2`
**Expect:** record the test APK size (about 119 MB as of 2026-10-19) and say where the size comes from (`aapt2 dump` or Android Studio's APK analyzer: ML Kit barcode models, Firebase, Compose). Suggest what could shrink for a budget phone (for example ML Kit's unbundled model, or a release build with R8, which the Play build will use).

## Battery

### GM-PERF-010 · Idle drain over 8 hours  `P0` `[PHONE]`
**Steps:** on the real phone with Nate's OK: charge to 100%, `adb shell dumpsys batterystats --reset`, unplug (stats only count unplugged; on USB use `adb shell dumpsys battery unplug` and Wi-Fi debugging, `adb tcpip` / `adb connect`), leave it with normal use for 8 hours or overnight. Then `adb shell dumpsys batterystats app.guardianmode.debug` (or a bugreport through Battery Historian).
**Expect:** Guardian's share projected to under 2% of a full charge per day. Report wakelocks, alarms, CPU time and network for Guardian. Restore with `dumpsys battery reset`.

### GM-PERF-011 · Polling behaves  `P1`
**Steps:** check the watch loop timing from `GuardianService`: screen off and no call should wait 30 s between checks; screen on, 3 s; during a call, 1 s with the screen on and 5 s off; armed or Locked, 1 s.
**Expect:** confirm with `adb shell dumpsys alarm` / `dumpsys activity services` and CPU sampling (`adb shell top -d 1 -n 10`, looking for Guardian's process) that the service is idle most of the time with the screen off. The Locked level polls every second whenever the screen is on: measure what that costs over an hour of screen-on use compared with Simple.

### GM-PERF-012 · Battery with a long call  `P2`
**Expect:** a 30-minute unknown call with the screen off doesn't add noticeable drain beyond the call itself.
