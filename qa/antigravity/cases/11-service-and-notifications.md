# 11 · Background service and notifications

`protection/GuardianService.kt`, `protection/Notifications.kt`, `protection/Receivers.kt`. Play requires a visible, user-stoppable notification while protection runs (`docs/POLICY.md` §1 rule 4 and §2). The service must survive normal phone life on a budget phone.

### GM-SVC-001 · The persistent notification  `P0` `[AVD]` `[PHONE]`
**Expect:** after setup, a notification "Guardian Mode is on" / "Tap to see what your family can see." with Guardian's own icon and two buttons, "Ask for help" and "Pause for 1 hour". Tapping it opens "What my family can see" (record where it actually lands). Its channel is "Guardian Mode is on" with the description from `res/values/strings.xml`.

### GM-SVC-002 · Swiping it away  `P1`
**Steps:** try to swipe the notification away on API 33, 34+ and the real phone.
**Expect:** record whether it can be dismissed (Android 14+ lets users dismiss most foreground-service notifications) and, if so, whether protection keeps working afterwards (standard call + Chase) and whether the notification comes back.

### GM-SVC-003 · Stopping it from Android's "Active apps"  `P0`
**Steps:** API 33+: pull down Quick Settings, tap the "N active apps" button at the bottom, tap Stop next to Guardian Mode.
**Expect:** record exactly what happens: does protection stop? Does it come back on its own (next unlock, next call, next Home press)? How long is the parent unprotected, and does the home chip or anything else say so? In Track B: does the family learn about it? This is the "user-stoppable" requirement; it must work, but the parent and family should know protection is off.
**Pass rule** (from `docs/SPEC.md` design rule 3, "the parent can see and stop everything", and `docs/POLICY.md` §1 rule 4, user-stoppable): (1) Stop works: within 10 s the service is gone and so is the "Guardian Mode is on" notification. (2) It stays stopped while the parent is in another app (not back within 15 s), and it never runs without its notification. (3) The next Home press brings protection back with "Guardian Mode is on" within 30 s, since Guardian is the home app. (4) Nothing keeps it from coming back at the next boot: Android hasn't marked it stopped (a stopped app gets no BOOT_COMPLETED; then GM-SVC-006 covers the reboot), or, if it has, Guardian is still the home app the boot opens (GM-SVC-008's path). While it is stopped the parent is unprotected and the missing notification is what tells them; the family side (Track B, the check-in heartbeat) is still for a person to record.
**In the cloud:** `device_checks.py` `gm_svc_003_stoppedFromActiveAppsComesBackOnHome` (API 36; skipped on API 29, which has no Active apps list) runs the Stop button's own system call with `am stop-app` while the Notes stand-in is in front, then checks the four points above without rebooting (each reboot risks the CI emulator not coming back). The report's measurements give Android's stopped flag after Stop and the time from Home to protection being on again.

### GM-SVC-004 · "Ask for help" button  `P1`
**Expect:** opens Help (GM-HELP-001).

### GM-SVC-005 · "Pause for 1 hour" button  `P0`
**Expect:** pauses (notification switches to "Guardian Mode is paused" with "Turn back on"). See suspect S-03 about the lock code and S-05 about the text afterwards.

### GM-SVC-006 · Survives reboot  `P0`
**Steps:** reboot the emulator (`adb reboot`; check `/proc/sys/kernel/random/boot_id` changed, so it really rebooted), unlock.
**Expect:** the notification is back within a minute of boot (BootReceiver), before the parent opens anything. Pause cards work (standard call + Chase). Record the time from unlock to notification.

### GM-SVC-007 · Survives an app update  `P0`
**Steps:** `adb install -r` the same APK (or a newer testing build).
**Expect:** service comes back by itself (MY_PACKAGE_REPLACED), settings and lock code intact, still the home app.

### GM-SVC-008 · Survives force stop  `P1`
**Steps:** `adb shell am force-stop app.guardianmode.debug`. Don't touch anything for 2 minutes. Then press Home.
**Expect:** while force-stopped Android won't deliver broadcasts, so protection is off until something starts the app. Pressing Home starts it again (Guardian is the launcher). Record how long until the notification is back and protection works. Note: force stop is what Settings > Force stop does, and what some OEM "cleaners" do.

### GM-SVC-009 · Doze and app standby  `P1`
**Steps:** screen off, `adb shell dumpsys deviceidle force-idle`, then `adb emu gsm call 5559998888`, accept, talk 40 s, hang up. Unforce, unlock, open Chase.
**Expect:** card. Also check that the battery exemption was granted (`dumpsys deviceidle whitelist` lists Guardian) and repeat with it removed: record the difference.

### GM-SVC-010 · Low memory  `P1`
**Steps:** on `gm-api29-budget`, open many heavy apps (Chrome with tabs, camera, maps if present), then do GM-PAUSE-001.
**Expect:** the service is still alive (`adb shell dumpsys activity services app.guardianmode.debug`) and the card appears. Record if Android killed it.

### GM-SVC-011 · Notification permission denied  `P1`
**Steps:** API 33+: revoke POST_NOTIFICATIONS.
**Expect:** the service still runs (foreground services run without the permission, but the notification is hidden in the shade; check the "Active apps" list shows it). Cards that fall back to notifications won't show; record what the parent sees. Home chip "Check me" if reminders were agreed to.

### GM-SVC-012 · Notification channels  `P2`
**Expect:** four channels with plain names: "Guardian Mode is on", "Pause cards", "Reminders", "From your family". Turning off "Pause cards" in Settings: record whether the fallback card notifications disappear and whether Guardian notices.

### GM-SVC-013 · Battery: the service sleeps when it should  `P1`
**Steps:** see GM-PERF-010. Check the poll loop: with the screen off and no call, logcat or `dumpsys` should show the service mostly idle (30 s waits).

### GM-SVC-014 · The notification always says the state the parent just chose  `P1`
**Steps:** from the shade, "Pause for 1 hour", then at once "Turn back on"; repeat a few times, quickly.
**Expect:** after each tap the notification settles on the state just chosen ("Guardian Mode is paused" / "Guardian Mode is on") and stays there.
