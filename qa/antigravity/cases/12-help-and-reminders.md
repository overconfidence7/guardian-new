# 12 · Help me, and reminders

## Help me

`docs/SPEC.md` F10, `ui/help/HelpActivity.kt`, `ui/help/ScreenCaptureService.kt`. The parent taps Help, Android asks "Start recording or casting?" every single time, Guardian takes one picture, shows it ("Send this to Sarah?"), and sends it only on yes, with a note picked from big buttons ("I'm stuck", "Is this real?", "Please call me").

### GM-HELP-001 · Help in solo mode  `P1`
Suspect S-07. **Steps:** solo default, tap the Help tile, and the notification's "Ask for help".
**Expect:** record exactly what's on screen and what the parent can do. A page with no button that helps is a bug: suggest what solo Help should do (call a saved number? explain?).

### GM-HELP-002 · Help with family but not linked  `P1`
**Setup:** family state injected (Sarah has a number), not paired.
**Expect:** "Ask Sarah for help", "You can call your family for help.", and a "Call Sarah" button that dials her. No screenshot option (not linked).

### GM-HELP-003 · Screenshot flow  `P0` `[B]`
**Setup:** Track B, paired.
**Steps:** open a stand-in app, then Help from the notification's "Ask for help". "Take a picture of my screen". Android's consent dialog: Start (on Android 14+ choose "entire screen" if asked).
**Expect:** Guardian steps out of the way, takes one picture of what was behind it (the stand-in, not Guardian's Help page), shows a notification "Your picture is ready" / "Tap to look at it and send it to your family.", then the preview "Send this to Sarah?" with the picture, "Add a note" with the three notes, "Send to Sarah" and "Don't send". The screen-recording indicator goes away right after the one picture (check the status bar; no ongoing capture).

### GM-HELP-004 · Android asks every time  `P0` `[B]`
**Expect:** a second Help, minutes later, shows Android's consent dialog again. Cancelling it changes nothing and sends nothing.

### GM-HELP-005 · "Don't send"  `P0` `[B]`
**Expect:** nothing uploaded (Storage emulator `help/` folder unchanged, no `HELP_REQUESTED` event), the picture file deleted from the app's cache (`run-as ... ls cache/help`).

### GM-HELP-006 · "Send"  `P0` `[B]`
**Expect:** "Sending...", then "Sent" / "Sarah will see your picture and your note." with "Call Sarah now" and "Done". In the emulator: one JPEG under `families/<f>/devices/<d>/help/`, one `HELP_REQUESTED` event with the note and path, the dashboard shows it with the picture. The local copy is deleted.

### GM-HELP-007 · Send while offline  `P1` `[B]`
**Steps:** airplane mode, then send.
**Expect:** "It didn't send" / "The phone may not be connected to the internet. You can try again, or call instead." with "Try again" and "Call Sarah". After reconnecting, "Try again" works.

### GM-HELP-008 · What the picture shows  `P1` `[B]`
**Expect:** the screenshot never includes Guardian's own Help page or the consent dialog. Check the size (should be screen resolution, JPEG).

## Reminders

`docs/SPEC.md` F11, `reminders/ReminderScheduler.kt`, `core/ReminderSchedule.kt`. Big full-screen card at the set time with the title and detail, "Done" and "Remind me in 10 minutes". If "Tell me if missed" is ticked and Done isn't tapped within 60 minutes, the family gets a notice.

Reminders come from the family's config. In Track A, inject them (`templates/state-family.json`, with times a few minutes from now), then force-stop and relaunch so Guardian schedules them. In Track B, set them from the dashboard's Reminders tab.

### GM-HELP-020 · Reminder card on time  `P0` `[AVD]` `[PHONE]`
**Steps:** a reminder 3 minutes from now, phone unlocked on the home screen.
**Expect:** at the minute (within a minute; record the delay), a full-screen card with the time, "Blood pressure pill", "One white tablet with water", "Done", "Remind me in 10 minutes". With exact alarms denied: record how late it is (should still come, within the 5-minute window).

### GM-HELP-021 · Reminder while the phone is locked  `P0`
Suspect S-16. **Steps:** lock the screen, let a reminder fire.
**Expect (per spec):** the parent sees the reminder without unlocking, or at least a notification on the lock screen. Record what actually happens.

### GM-HELP-022 · Reminder while another app is open  `P1`
**Expect:** the card covers it. Back returns to that app.

### GM-HELP-023 · Done  `P0`
**Expect:** card closes; "Today's reminders" shows it done; state `remindersDone` has the occurrence. No missed-reminder event an hour later.

### GM-HELP-024 · Snooze  `P1`
**Expect:** "Remind me in 10 minutes" closes it and it comes back 10 minutes later. Snooze three times in a row: it keeps coming back. Record whether snoozing past 60 minutes counts as missed.

### GM-HELP-025 · Missed  `P0`
**Steps:** a reminder with `notifyFamilyIfMissed: true`; ignore the card (press Home). Move the clock 61 minutes.
**Expect:** a `REMINDER_MISSED` event with `title` (and `time`). Transparency list: ""Blood pressure pill" wasn't marked done." A reminder without the tick: no event.

### GM-HELP-026 · Today's reminders list  `P1`
**Expect:** every occurrence today in time order, done ones marked. "Nothing else today." when empty. Weekday-only and one-date reminders appear only on their days (change the date).

### GM-HELP-027 · Reminders after reboot, time zone change and clock change  `P1`
**Expect:** after reboot, reminders still fire. After changing the time zone, they fire at the local time in the new zone (record the behavior; check what `checkIn.timezone` does to them).

### GM-HELP-028 · Do Not Disturb  `P2`
**Expect:** record whether the reminder card appears with DND on. A medicine reminder hidden by DND is worth a line in the report.
