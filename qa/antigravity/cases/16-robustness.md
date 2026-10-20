# 16 · Robustness

Budget phones kill apps, lose signal, run out of storage, and get their settings changed by grandchildren. Guardian has to keep working, and when it can't, say so.

### GM-ROB-001 · Process death everywhere  `P1`
**Steps:** on each screen in turn (setup pages, lock settings, a card, Help preview, the share page), press Home, `adb shell am kill app.guardianmode.debug`, return via Recents or by repeating the action.
**Expect:** no crash; a sensible screen; no half-saved state (for example, a lock level saved without a code). Record anything lost.

### GM-ROB-002 · Don't keep activities  `P2`
**Steps:** Developer options > "Don't keep activities" on. Repeat setup and a pause card flow.
**Expect:** works. Note any screen that loses what was typed or chosen.

### GM-ROB-003 · Corrupt saved state  `P1`
**Steps:** force-stop, write `{"config": 5}` into `guardian_state.json`, relaunch. Then an empty file. Then valid JSON with an unknown field and a future `schemaVersion: 2`.
**Expect:** no crash loop. Corrupt → starts fresh (by design it replaces the file). Unknown fields → ignored, everything else kept. Record what the parent sees; a silent reset of the lock and consent is worth a line in the report.

### GM-ROB-004 · Storage full  `P2`
**Steps:** fill the emulator's storage (`adb shell dd if=/dev/zero of=/sdcard/fill bs=1m count=<n>`) until a few MB are left, then add photos, trigger events, change settings.
**Expect:** no crash; record what fails and what the parent sees. Clean up afterwards.

### GM-ROB-005 · Airplane mode and flaky network  `P1`
**Expect:** in solo mode, nothing changes. In Track B: everything on the phone keeps working offline (cards, lock, screening), events queue (`outbox`) and send later (GM-FAM-041), no error dialogs.

### GM-ROB-006 · Time zone and clock changes  `P1`
**Steps:** change the time zone across midnight, change to a zone with a half-hour offset (Asia/Kolkata), turn automatic time off and set the date a year ahead, then back.
**Expect:** the home clock and greeting follow. Reminders and the check-in follow the rules in GM-HELP-027. Lock waits and unlock windows don't get stuck (GM-LOCK-055). Pause end time is shown in the new zone.

### GM-ROB-007 · Locale and right-to-left  `P2`
**Steps:** switch the system language to Spanish, then Arabic (right-to-left).
**Expect:** the app is English-only today, so English text is fine; but layouts mirror cleanly in RTL with nothing overlapping, dates and times use the locale's format where Guardian formats them, and nothing crashes. Note any hard-coded US formats.

### GM-ROB-008 · Dark mode and theme switch while open  `P2`
**Expect:** switching the system theme while a card is up redraws it correctly; Guardian's own LIGHT/DARK/SYSTEM setting (family state) wins over the system where set.

### GM-ROB-009 · Multiple users and work profile  `P2`
**Steps:** create a second user on the emulator (`adb shell pm create-user test`), switch to it.
**Expect:** Guardian in the main user isn't affected; record what happens in the second user (it's not installed there). Note whether a scammer could use a guest user to get around the lock at Locked (switching users from Quick Settings): that's worth testing at Locked and reporting.

### GM-ROB-010 · Another app takes the home role  `P1`
**Steps:** install a third-party launcher (on the Play image) and pick it as home.
**Expect:** Guardian notices (home chip "Check me" when opened, Track B status "Needs attention"). At Locked, can the parent switch home apps without the code? (Settings is covered, but the launcher's own "set as default" prompt may not be; try it.)

### GM-ROB-011 · Uninstall and reinstall  `P1` `[AVD]`
**Expect:** after uninstall + reinstall, setup starts fresh; nothing from before survives (no backup). On the real phone, don't do this without Nate.

### GM-ROB-012 · Upgrade from the previous testing build  `P0` `[PHONE]`
**Steps:** the phone already has an older testing build in solo mode with Nate's settings. With Nate's OK, install the current `guardian-mode-test.apk` over it (`adb install -r`).
**Expect:** installs over (same test key), keeps setup, lock level, code, photos and consent. The service restarts by itself. Record the version before and after.

### GM-ROB-013 · Rapid taps  `P2`
**Steps:** double- and triple-tap every button that starts something: "Turn on", "Continue", "Call Sarah first", "Send", "Save this code", "Unlock".
**Expect:** no duplicate calls, no two cards, no double events, no stuck spinners.

### GM-ROB-014 · Rotation and folding  `P2`
**Steps:** on a foldable or tablet emulator profile (Pixel Fold, a 10" tablet), go through setup and home.
**Expect:** portrait lock works or the layout still fits; nothing unusable. Record.

### GM-ROB-015 · Dialer and messages apps missing or replaced  `P2`
**Steps:** disable the stock Messages app (`pm disable-user`), make a third-party SMS app the default, then none.
**Expect:** the Messages tile opens the default SMS app or falls back; never a crash or a dead tap without explanation.
