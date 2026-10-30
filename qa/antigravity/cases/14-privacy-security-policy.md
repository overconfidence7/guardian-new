# 14 · Privacy, security and Play policy

The promise (`docs/SPEC.md` bar 6, `docs/POLICY.md`, `docs/SECURITY.md`): events are shared, never content. Nothing leaves the phone that isn't in the event list: no numbers, no messages, no usage history, no location. No Accessibility API, no SMS or call-log permissions, Usage access only to see which app is in front. The app never says "monitor".

## What leaves the phone

### GM-PRIV-001 · Network traffic in solo mode  `P0`
**Steps:** test APK, solo. Capture traffic while you run setup, a pause card, a remote-control warning and a long call: on `gm-api36` use the emulator's `-tcpdump capture.pcap` start option, or `adb shell` + a proxy if you set one up. Open the pcap in Wireshark (install only with Nate's OK) or summarize with `tshark`.
**Expect:** no connections at all from Guardian except, at most, Firebase SDK start-up attempts to Google hosts that fail harmlessly (the test build points at a fake project). List every host contacted. Anything carrying event data in solo mode is a P0.

### GM-PRIV-002 · Everything written to Firestore  `P0` `[B]`
**Steps:** after the full Track B run, export the Firestore emulator (`npx firebase emulators:export` while running, or browse the UI) and list every document under the device.
**Expect:** the device doc holds only model, Android version, app version, status (protected, missing permission keys, heartbeat timestamps), installed launchable apps (package and label, for the dashboard's picker), consent answers, and the FCM token. Events hold only the keys in `copy.ts`'s header comment. No phone number in any form, no contact names other than what the family typed, no message text, no list of when apps were used, no location. Grep the export for every number you dialled or received.

### GM-PRIV-003 · The installed-apps list  `P1` `[B]`
**Expect:** the device reports launchable apps so helpers can pick tiles. Note that this is shared with every helper, and check the consent page says so ("The list of apps on this phone, so they can add buttons for you." on page 1). Say whether that's clear enough to a parent (it reveals, for example, a dating or health app).

### GM-PRIV-004 · Logcat leaks  `P1`
**Steps:** capture full logcat for a whole Track A session.
**Expect:** no phone numbers, no safe word, no lock code, no contact names, no photo paths from other apps. Search for them.

### GM-PRIV-005 · No backups  `P1`
**Steps:** `adb shell bmgr backupnow app.guardianmode.debug` (enable backup transport first on the emulator if needed); check `data_extraction_rules.xml` in the APK.
**Expect:** nothing of Guardian's is backed up or transferred to a new device (`allowBackup=false`, extraction rules exclude everything).

### GM-PRIV-006 · Files are private  `P1`
**Expect:** everything Guardian stores is under its private data dir (`run-as ... ls -R`). Nothing on `/sdcard`. Help screenshots in `cache/help` are deleted after send or discard.

## Components other apps could poke

### GM-PRIV-010 · Non-exported screens can't be opened  `P0`
**Steps:** `adb shell am start -n app.guardianmode.debug/app.guardianmode.ui.cards.GuardianCardActivity`, same for `.ui.onboarding.OnboardingActivity` and `.ui.help.HelpActivity`, and `am startservice` for `GuardianService`. Also from a stand-in app if you add a test button (optional).
**Expect:** "Permission Denial" / "not exported". A fake pause card or a fake "Stop using" from another app must be impossible.

### GM-PRIV-011 · Fake broadcasts  `P0`
**Steps:** `adb shell am broadcast -a android.intent.action.PHONE_STATE --es state OFFHOOK -n app.guardianmode.debug/app.guardianmode.protection.PhoneStateReceiver`; `adb shell am broadcast -a app.guardianmode.PAUSE_HOUR -n app.guardianmode.debug/app.guardianmode.protection.NotificationActionReceiver`; `am broadcast -a android.intent.action.BOOT_COMPLETED -n .../.protection.BootReceiver`.
**Expect:** PHONE_STATE is a protected broadcast (shell can't send it, or the receiver's permission check stops it); PAUSE_HOUR is refused (receiver not exported). BOOT_COMPLETED from shell may be refused too; if it gets through, it only starts the service (harmless; note it). If any fake call state or pause gets through from shell, also try from a stand-in app with no permissions: that's the real attacker.

### GM-PRIV-012 · The share target  `P1`
Suspect S-19. **Steps:**
```powershell
adb shell am start -a android.intent.action.SEND -t image/jpeg --eu android.intent.extra.STREAM file:///data/data/app.guardianmode.debug/files/datastore/guardian_state.json -n app.guardianmode.debug/app.guardianmode.ui.home.SharePhotosActivity
```
Also a `content://` URI from another app without a grant, a 100-image `SEND_MULTIPLE`, and a text/plain stream with an `image/*` type.
**Expect:** none of Guardian's own files can be turned into a photo; refused URIs show "That didn't work"; no crash; no flood beyond the 30-photo cap.

### GM-PRIV-013 · Screen capture of sensitive screens  `P1`
Suspect S-02. `screencap` and screen recording on: safe word revealed, transparency screen, lock code entry, Help preview. Also the Recents thumbnail for each.
**Expect (per `docs/SECURITY.md` §4):** black for the safe word and transparency screens. Record what's actually captured.

### GM-PRIV-014 · Lock code storage  `P0`
Covered by GM-LOCK-003. Also confirm the code is never sent: in Track B, it appears in no Firestore document.

## Play policy checks

### GM-PRIV-020 · Every "never shared" claim holds  `P0` `[B]`
Take each line of "Never shared" on the transparency screen and each "Never shared" on the consent pages, and say how you verified it (traffic capture, Firestore export, code reading). Any claim you can't verify, list as unverified.

### GM-PRIV-021 · Disclosure before collection  `P0`
**Expect:** no permission or data collection starts before the matching consent page's "Turn on" (GM-CONS-001). In Track B, no device doc writes (status, installed apps) happen before the parent finishes the consent pages; check timestamps in Firestore against when each page was tapped.

### GM-PRIV-022 · Visible and stoppable  `P0`
**Expect:** while protection runs there's always the notification (GM-SVC-001) and the parent can stop protection (pause, switches, Stop using, Android's Active apps). Guardian has its own icon in the launcher list. Nothing hides the app.
**In the cloud:** `PrivacyDeviceTest.gm_priv_022_gm_inst_001_visibleInLauncherAndShadeAndStoppable`: one launcher entry with Guardian's name and icon (as `LauncherApps` lists it), a foreground service with an ongoing notification on a channel that isn't off and its "Pause for 1 hour" button, and "Stop using Guardian Mode" on the transparency screen. Stopping from "Active apps" is GM-SVC-003.

### GM-PRIV-023 · No banned capabilities in practice  `P0`
**Expect:** across the whole run, Guardian never asks for or uses Accessibility, SMS, call log or location. Check `adb shell dumpsys package app.guardianmode.debug` for requested and granted permissions at the end of the run and `appops get` for any op it touched. Report the full list.
**In the cloud:** `device_checks.py` `gm_priv_023_noBannedCapabilitiesInPractice` (API 29 and 36), after setup, an unknown call and a pause card: no banned permission requested or granted, no banned app op used or refused, not an enabled accessibility service. The report's measurements list the requested and granted permissions and every app op used.

### GM-PRIV-024 · The word "monitor" and friends  `P0`
**Steps:** search every user-visible string: `git grep -n -i -E "monitor|track|spy|surveil|watch(es|ing)? (you|mom|dad)" -- android/app/src/main web/src/screens web/src/components firebase/functions/src/copy.ts`, and look at every screen you screenshot.
**Expect:** "monitor" appears nowhere a user can see it (code comments are fine). Report each hit in user-facing text with file and line.
