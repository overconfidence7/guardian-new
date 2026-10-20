# 03 · Lock levels and the lock code

What should happen (`docs/SPEC.md` F13, `core/LockPolicy.kt`):

| Needs the code? | Simple | Guided | Locked |
|---|---|---|---|
| "Stop using Guardian Mode" | yes | yes | yes |
| Phone lock settings | yes | yes | yes |
| "All my apps" | no | yes | yes |
| "Phone settings" (in All my apps) | no | yes | yes |
| Protection switches, "Pause everything for 1 hour", removing a helper | no | yes | yes |
| Any app the family didn't add (Settings, Play Store, package installer included) | no | no | yes: covered by a code screen |
| Calls, dialer, emergency, system dialogs, permission dialogs | never | never | never |

A correct code opens everything for 10 minutes. Five wrong tries start a wait: 30 s after the 5th, then 1, 2, 4, 8, 16 minutes (capped). Only a salted hash is stored.

Use a code like `2580` for tests. Use "fast setup" (RUNBOOK §7) after the first manual pass of 02.

## Setting the code

### GM-LOCK-001 · Lock level choice during setup  `P0`
**Expect:** after the last consent page, "How locked down should this phone be?", "You can change this later with the code.", three cards Simple / Guided / Locked with the descriptions from `ui/lock/LockScreens.kt`, Guided preselected, "Next". TalkBack announces them as radio buttons with selected state. Locked's description says "Calls always work, including 911."

### GM-LOCK-002 · Code rules  `P0`
**Steps:** on "Pick a lock code": try `123` + Next; try letters (keyboard should be numeric only); try 9 digits; try `2580` + Next then `2581` + Save; then `2580` / `2580`.
**Expect:** `123` → "The code needs at least 4 numbers."; letters can't be typed; input stops at 8; mismatch → "Those didn't match. Start again." and both fields cleared, back to the first step; match → "You're all set." Digits are hidden as dots while typing.

### GM-LOCK-003 · Only a hash is stored  `P0`
**Steps:** read the state JSON.
**Expect:** `lockCodeHash` is 64 hex characters, `lockCodeSalt` a UUID, and the code itself appears nowhere in the state file, logcat, or `adb shell run-as app.guardianmode.debug ls -R` file contents (grep the pulled files for `2580`).

### GM-LOCK-004 · Back from "You're all set"  `P2`
**Expect:** Back on the Done screen returns to the lock level choice (a code exists). Choosing a different level and Next goes to "Pick a lock code" again. Record whether setting a new code there replaces the old one (it should).

## Simple

### GM-LOCK-010 · Simple leaves the phone open  `P0`
**Setup:** level Simple, code set.
**Expect:** "All my apps" opens without a code. "Phone settings" opens Android Settings without a code. Every other app opens normally. Protection switches on "What my family can see" toggle without a code.

### GM-LOCK-011 · Simple still guards leaving and the lock  `P0`
**Steps:** "What my family can see" > "Change the lock"; then "Stop using Guardian Mode" > "Stop".
**Expect:** both ask for the code first ("Enter the code", "This part of the phone needs the lock code."). Back from the code screen returns to where you were and does nothing else.

## Guided

### GM-LOCK-020 · Guided guards the app list, settings and switches  `P0`
**Setup:** level Guided, locked (wait out or "Lock it again now").
**Steps:** tap "All my apps"; open the transparency screen and tap a protection switch; tap "Pause everything for 1 hour"; tap "Change the lock".
**Expect:** each one asks for the code. After the right code, the action you tapped happens by itself (the app list opens, the switch flips, the pause starts). Back from the code screen cancels it.

### GM-LOCK-021 · Guided leaves other apps alone  `P1`
**Expect:** at Guided, Settings opened from the notification shade's gear icon opens normally (only Locked covers other apps). Note this in the report as expected but worth a line in the transparency text.

### GM-LOCK-022 · Phone settings inside All my apps  `P1`
**Steps:** Guided, unlock to open All my apps, wait for the 10 minutes to pass (move the clock), tap "Phone settings".
**Expect:** asks for the code again.

## Locked

### GM-LOCK-030 · Settings is covered within a second  `P0` `[AVD]` `[PHONE]`
**Setup:** Locked, code set, locked, Usage access and overlay granted, Guardian is the home app.
**Steps:** pull down the shade, tap the gear. Start `adb shell screenrecord` first.
**Expect:** a full-screen card "Settings is locked", "This phone is set up to keep things simple. To open Settings, enter the code.", a code box labelled "Your code", "Unlock" and "Go to my home screen". Measure from the Settings window appearing to the card covering it, frame by frame (spec target: within a second; suspect S-10). Record what was visible and tappable in between.

### GM-LOCK-031 · Every way into Settings is covered  `P0` `[AVD]` `[PHONE]`
Try each and record covered / not covered / time:
- the gear in the notification shade
- long-press a Quick Settings tile (Wi-Fi, Bluetooth) which opens its Settings page
- long-press any notification > the settings cog / "App info"
- `adb shell am start -a android.settings.SETTINGS` and `-a android.settings.APPLICATION_DETAILS_SETTINGS -d package:app.guardianmode.debug`
- the "Settings" item inside the dialer's menu
- the Google search bar or Assistant ("open settings")
- the volume panel's settings button
- the battery or storage notification
**Expect:** every one ends on the code screen. Anything that leaves Settings usable is a P0 bug.

### GM-LOCK-032 · Uninstalling Guardian is covered  `P0` `[AVD]`
**Steps:** at Locked, try: Settings > Apps > Guardian > Uninstall (should be covered before you get there); `adb shell am start -a android.intent.action.DELETE -d package:app.guardianmode.debug`; the Play Store app page (on `gm-api36-play`).
**Expect:** the package installer / Play Store is covered by a code screen. Record the package name of the uninstall dialog on each API level (it differs: `com.android.packageinstaller`, `com.google.android.packageinstaller`) and that it's covered. Safe mode (hold Power off) still works and is documented as the way out; confirm it on `gm-api36`.

### GM-LOCK-033 · Calls and emergency always work  `P0` `[AVD]` `[PHONE]`
**Steps:** at Locked: tap the Phone tile and dial; receive a fake call (`adb emu gsm call 5550101111`) and answer; from the lock screen tap Emergency call; open the dialer from a "Call ___" tile; open the in-call screen's keypad and speaker.
**Expect:** no code screen appears over any of them, at any point in the call. On the real phone, open the emergency dialer from the lock screen only (don't call 911).

### GM-LOCK-034 · Permission dialogs and the share sheet aren't covered  `P1`
**Steps:** at Locked, trigger a runtime permission prompt from an allowed app (e.g. the camera from the Camera tile), and the share sheet from the photo app.
**Expect:** not covered. Then try to use those to reach something not allowed (the share sheet's "Edit" or "App info", the permission dialog's "Settings" link): the next screen should be covered. See suspect S-12 for the permission controller.

### GM-LOCK-035 · Apps the family added are allowed  `P0`
**Setup:** inject `templates/state-family.json` (Notes is a tile and in allowedApps). Install the Notes, Chase and Venmo stand-ins.
**Expect:** Notes opens freely. Chase and Venmo are covered (they're not on the phone's list). Phone, Messages and Camera tiles open freely.

### GM-LOCK-036 · Back on a locked app's cover goes home  `P0`
**Steps:** at the "Chase is locked" card, press Back; then open Chase again and press Home; then open Chase from Recents.
**Expect:** Back goes to Guardian's home screen, never into Chase. Home goes home. Recents → Chase is covered again.

### GM-LOCK-037 · The right code opens everything for 10 minutes  `P0`
**Steps:** on "Chase is locked", enter the code. Use Chase. Open Settings and Venmo. Move the clock forward 9 minutes, open Settings again, then 2 more minutes and open Settings again.
**Expect:** after the code, Chase opens (the card closes and Chase is in front). Settings and Venmo open freely during the 10 minutes. At 11 minutes, Settings is covered again. The code screens inside Guardian (All my apps etc.) follow the same 10-minute window.

### GM-LOCK-038 · "Lock it again now"  `P1`
**Steps:** unlock, go to "What my family can see" > "Change the lock" > "Lock it again now", then open Settings.
**Expect:** back to the home screen, and Settings is covered straight away.

### GM-LOCK-039 · Switching apps fast at Locked  `P1`
**Steps:** use Recents to flip between Notes (allowed), Chase (blocked) and Settings (blocked) as fast as you can, ten times. Also try split screen with Notes and Settings, and picture-in-picture if any app offers it.
**Expect:** a blocked app is never usable for more than about a second. No crash, no stack of duplicate cards (after the run, one Back press returns home).

### GM-LOCK-040 · The old launcher at Locked  `P1`
See suspect S-09. Try every way to bring up the phone's stock launcher: a home-app chooser, Assistant, a widget, a launcher shortcut from a notification. Record each.

### GM-LOCK-041 · Locked without overlay or Home role  `P1`
See suspect S-11. Remove the Home role and deny overlay with adb, open Settings.
**Expect (per spec):** the cover still appears. If only a notification appears, that's a bug; also check the transparency screen tells the parent something is missing.

### GM-LOCK-042 · Locked level and the notification shade  `P1`
**Steps:** at Locked, pull down Quick Settings. Turn on airplane mode, turn off Wi-Fi, turn on Do Not Disturb.
**Expect:** these work (Quick Settings isn't covered; that's acceptable). Note in the report which Quick Settings actions could hurt protection (airplane mode stops alerts reaching family; DND may silence reminders) so Nate can decide.

## Wrong codes and the wait

### GM-LOCK-050 · Wrong code message  `P0`
**Expect:** "That's not the code. Try again." The field clears. TalkBack reads the message (it's a polite live region).

### GM-LOCK-051 · Five wrong tries start a 30-second wait  `P0`
**Steps:** enter 5 wrong codes in a row. Note the time. Then try the right code straight away. Then wait 30 s and enter the right code.
**Expect:** after the 5th wrong try: "That's too many tries. You can try again at h:mm a." with a time about 30 s ahead (to the minute). The right code during the wait is refused with the same message. After the wait, the right code works and the counter resets (state JSON `lockFailures: 0`).

### GM-LOCK-052 · The wait doubles up to 16 minutes  `P1`
**Steps:** keep entering wrong codes after each wait ends (move the clock to skip waits).
**Expect:** waits of 30 s, 1, 2, 4, 8, 16, 16 minutes. Never longer than 16. Record each `lockRetryAt` from state.

### GM-LOCK-053 · The wait survives a restart  `P1`
**Steps:** during a wait, force-stop and reopen; reboot the emulator.
**Expect:** still waiting until the same time.

### GM-LOCK-054 · The wait is the same everywhere  `P2`
**Expect:** a wait started on "Chase is locked" also applies on the All my apps code screen, and vice versa.

### GM-LOCK-055 · Clock tricks don't skip the wait  `P2`
**Steps:** during a 16-minute wait, move the clock back an hour.
**Expect:** record what happens. If moving the clock back makes the wait longer or permanent, that's a bug (the parent is locked out). If moving it forward skips the wait, note it (Locked covers Settings, so the parent can't change the clock there anyway; check that's true).

## Changing things later

### GM-LOCK-060 · Change the level  `P0`
**Steps:** from "What my family can see" > "Change the lock" (with code), pick each level in turn and test one thing that differs.
**Expect:** the new level applies at once, and the transparency "Phone lock" text updates: Simple "Simple. The code is only needed to change the lock or stop using Guardian Mode.", Guided "Guided. The app list, phone settings and these switches need the code.", Locked "Locked. Any app your family didn't add, Settings included, needs the code. Calls always work."

### GM-LOCK-061 · Change the code  `P0`
**Steps:** "Change the code", set `1470` / `1470`. Lock again. Try `2580`, then `1470`.
**Expect:** old code refused, new code works. State JSON has a new salt and hash.

### GM-LOCK-062 · No code means nothing is locked  `P1`
**Steps:** remove the code by editing state (`lockCodeHash: ""`), set level Locked in state.
**Expect:** nothing asks for a code and nothing is covered (the code is the only way in, so no code means no lock). The transparency screen says "There's no lock code on this phone yet." and the button says "Set up a lock".

### GM-LOCK-063 · Forgotten code  `P1`
**Expect:** there's no reset on the phone in this build (helpers will reset it once linked). Write down exactly what a parent who forgot the code can do today, and whether the app tells them anywhere ("Write it down somewhere safe. If it's forgotten, the person who set up this phone can help." is the only hint). Confirm the safe-mode uninstall in `docs/TESTING.md` works on `gm-api36` and the real phone (ask Nate before doing it on the phone).

### GM-LOCK-064 · Stopping Guardian removes the lock  `P0`
**Steps:** "Stop using Guardian Mode" > "Stop" > enter code.
**Expect:** Android's Home settings opens so the parent can pick another home app. The lock, code and consent are cleared (state JSON). After picking another launcher, Settings and every app open normally. Reopening Guardian starts setup from the role choice. Guardian's persistent notification is gone.
