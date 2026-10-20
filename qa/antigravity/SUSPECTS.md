# Suspects

Problems spotted by reading the code on 2026-10-20 (commit 23dbb63), before any device testing. None of these is confirmed. For each one: reproduce it on a device (or show it can't happen), mark it **Confirmed**, **Ruled out** or **Partly**, attach evidence, and turn every confirmed one into a bug in the report. Line numbers may have drifted; search for the quoted code.

## Verdicts so far

From the partial run on 2026-10-19 (`2cd6b8e`, `gm-api36`). Fixes landed in PR #6. Re-check these on the next run rather than skipping them.

| ID | Verdict | Note |
|---|---|---|
| S-06 | Confirmed | "Alerts your family received" shown in solo mode; heading is now "What your family was told". The solo-mode honesty question is still open. |
| S-08 | Confirmed, fixed | Five parent-facing hits (BUG-005). |
| S-09 | Ruled out | At Locked the Pixel launcher got a code screen, but it showed the package name; now "This app is locked". |
| S-11 | Partly | Settings stayed uncovered in `GM-LOCK-041`; the run didn't record which permission was missing. Re-test. |
| S-12 | Confirmed, fixed | Contacts was switched off from the permission manager without the code. The manager is now covered; permission prompts still show. |

### S-01 · Track B pairing may fail: no cleartext exception for the emulator host  (blocks Track B)
`android/app/src/main/AndroidManifest.xml` has no `networkSecurityConfig` and there is no `src/debug` source set. Since Android 9, plain `http://` is blocked unless allowed, and the Firebase Auth, Functions and Storage emulators are plain HTTP on `10.0.2.2`. Firebase's own docs say Android apps need a cleartext exception for the emulator host.
- Check: Track B build, tap "Set up with my family", enter a real setup code from the dashboard. Look for `CLEARTEXT communication to 10.0.2.2 not permitted` in logcat, or the "That didn't work" message.
- If confirmed, the allowed local patch (never commit it): add `android/app/src/debug/res/xml/network_security_config.xml` permitting cleartext for `10.0.2.2`, `127.0.0.1` and `localhost`, and `android/app/src/debug/AndroidManifest.xml` setting `android:networkSecurityConfig="@xml/network_security_config"` on `<application>` (with `tools:replace` if needed). Record the patch in the report.

### S-02 · No FLAG_SECURE on the transparency screen or the safe word  (P1, security)
`docs/SECURITY.md` §4 promises FLAG_SECURE "on the transparency and safe-word screens so the safe word can't be captured by a screen-recording app". Nothing in `android/app/src/main` sets `FLAG_SECURE`.
- Check: open the safe-word card and tap "Show our safe word", then `adb shell screencap`. Same for the transparency screen and the Help preview. A secure window comes out black. Also check the Recents thumbnail.

### S-03 · "Pause for 1 hour" on the notification skips the lock code  (P0, scam)
In the app, "Pause everything for 1 hour" needs the code at Guided and Locked (`MainActivity.onPauseHour` uses `withCode(PROTECTION_SETTINGS)`). The persistent notification's "Pause for 1 hour" button goes straight to `ProtectionController.pauseFor` (`protection/Receivers.kt`, `ACTION_PAUSE_HOUR`) with no code. A caller can say "pull down from the top and tap Pause", and every pause card, warning and screening rule stops for an hour.
- Check at Guided and Locked: pull down the shade, tap "Pause for 1 hour". Is any code asked for? Then run a full scam sequence (unknown call, open Chase stand-in) and see if anything appears.

### S-04 · Pausing tells nobody  (P1, transparency)
`pauseFor` only stores `pausedUntil`; it records no event and changes no config, so helpers aren't told, and the next status report that would say `protected: false` can be six hours away. The spec says helpers are told when the parent turns something off.
- Check: Track B, pause from the app and from the notification, then look at the dashboard and the Firestore emulator for any event or status change.

### S-05 · The notification can keep saying "paused" after the hour is over  (P2)
`GuardianService` rebuilds the notification only when the stored state changes (`store.state.map { isPaused(now) }.distinctUntilChanged()`). Nothing changes when the hour simply runs out.
- Check: pause, move the clock 61 minutes forward (or wait), look at the notification text and its button, and at the shield chip on the home screen.

### S-06 · Solo mode lists "Alerts your family received" that nobody received  (P1, honesty)
`LocalStore.recordShared` adds every event to `sharedEvents` whether or not the phone is linked to a family. In the test build, the transparency screen will say the family received alerts that went nowhere.
- Check: solo mode, trigger a pause card, open "What my family can see". What does the alerts section say, and is it true?

### S-07 · The Help tile is a dead end in solo mode  (P1)
`HelpActivity`: the screenshot button needs pairing, and the call button needs a family member with a number. In solo mode neither exists, and the page says "You can call your family for help." with nothing to tap. The Help tile is on the default home screen.
- Check: solo mode, tap Help.

### S-08 · Parent-facing wording uses "scam" and "alert"  (P2, spec bar 9)
`docs/SPEC.md` §2.9: copy to the parent never says "scam", "fraud", "danger" or "alert". The helper confirmation screen says "get a note if something looks like a scam" (`OnboardingScreens.kt`, Confirm), and the transparency screen has a section titled "Alerts your family received". Search all parent-facing strings (see `cases/18-wording.md`).

### S-09 · At Locked, the phone's old launcher is always allowed  (P1, lock)
`LockManager.allowedPackages()` adds every app that answers `CATEGORY_HOME`, so the phone's own launcher (Pixel Launcher, One UI Home) is never covered. If anything can bring it to the front (a "Home" choice dialog, an Assistant command, a shortcut, a widget), the parent has the whole phone without the code.
- Check on Pixel and Samsung images or the real phone: try every way you can find to open the stock launcher while Guardian is the home app at Locked.

### S-10 · At Locked, covered apps are usable for about a second  (P1, lock)
Locked relies on polling Usage access every second (`GuardianService.watchLoop`) and then starting a card. Measure how long a blocked app is visible and usable. Try fast sequences that finish something harmful inside that window, for example a Settings deep link that opens Guardian's app info with "Force stop" under the thumb.

### S-11 · At Locked, without "Display over other apps" or the Home role, the cover is only a notification  (P1, lock)
`CardLauncher.show` starts the card directly only when overlay is granted or Guardian is the home app; otherwise it posts a notification. A notification doesn't cover anything.
- Check: Locked level, Guardian not the home app and overlay denied (set with adb), open Settings.

### S-12 · At Locked, the permission controller is always allowed  (P1, lock)
`LockPolicy.ALWAYS_ALLOWED` includes `com.android.permissioncontroller` and `com.google.android.permissioncontroller`. That app also hosts the Privacy dashboard and the permission manager, reachable from the green camera/mic dot in the status bar and from Quick Settings. From there, can someone revoke Guardian's Phone or Contacts permission without the code?

### S-13 · Skipping call screening means pause cards never arm  (P0, scam)
`CallTracker`: when no screening result arrives, a ringing call counts as unknown only if `READ_CONTACTS` is granted. If the parent says yes to calls but the screening role isn't held (skipped, or the OEM refused), no call is ever screened, so with Contacts off every call counts as known and tripwires never arm. With Contacts on, every call counts as unknown, so calls from Sarah arm them (breaks spec bar 4, "zero false alarms from family calls").
- Check all four combinations of screening role and Contacts, with a call from a family number and from an unknown number, then open the Chase stand-in.

### S-14 · Calls the parent places never arm the tripwires  (P1, scam)
`CallTracker`: off-hook straight from idle is an outgoing call and is treated as known. "Hang up and call me back on this number" is a common scam move. Confirm the behavior and say in the report whether it should change (that's a product call for Nate).

### S-15 · "Go to my home screen" on a pause card uses up the card for that call  (P2)
The card shows once per app category per call (by design). Closing it with "Go to my home screen" records nothing, but the category stays marked, so opening the bank app again during the same call goes straight in. Confirm, and say whether the second open should ask again.

### S-16 · Full-screen cards don't show over the lock screen  (P1, reminders)
`GuardianCardActivity` is `showWhenLocked="false"`. When Guardian can start the card directly (overlay or home role), `CardLauncher` doesn't post a notification at all. A reminder that fires while the phone is locked may turn the screen on and show only the lock screen, with the card hidden behind it and no notification.
- Check: lock the phone (`adb shell input keyevent 26`), let a reminder fire (inject one a few minutes out, see the runbook), and look at the screen and the shade.

### S-17 · Helpers can make "risky" changes without the parent  (P0, security, Track B)
`docs/SECURITY.md` §3 says changing a family member's phone number or the primary helper, trusting a remote-control app, turning off pause cards, the remote-control warning or long-call notices from the dashboard, and remote unlock are held until the parent approves them on the phone. The Firestore rules let any helper write the whole config (`firestore.rules`, `match /config/{docId}`), and nothing in the functions holds changes as pending.
- Check in Track B: as a helper, change Sarah's number, mark AnyDesk trusted, turn pause cards off, and set `lock.remoteUnlockUntil` (via the dashboard if it offers it, otherwise a direct Firestore write with the helper's own sign-in from the browser console). Does the phone apply any of it without asking the parent?

### S-18 · Pause everything also lets every unknown caller ring  (P2)
`GuardianCallScreeningService` allows every call while paused. Probably intended; confirm and note it on the transparency screen check (does the parent know pausing turns screening off too?).

### S-19 · The share target accepts anything another app sends  (P2, security)
`SharePhotosActivity` is exported and decodes whatever URI arrives. Try a `file://` URI pointing at Guardian's own private files, a huge image, a non-image with an `image/*` type, and 100 images at once.

### S-20 · The default lock level is Guided  (note)
Setup preselects Guided (`OnboardingActivity`, `lockLevel = LockLevel.GUIDED`). Not wrong, but say in the report whether that's the right default for a first-time parent, after using it.
