# 04 · Home screen

What the spec promises (`docs/SPEC.md` F1, bar 1): very large time and date, the photo card, a grid of big tiles, a "next reminder" strip when one is due within 2 hours, a shield chip, and "All my apps" at the bottom. A parent who has never seen the app can call their daughter within 5 seconds.

Run the solo default first (no injected state), then with `templates/state-family.json` injected.

### GM-HOME-001 · Default solo home screen  `P0` `[AVD]` `[PHONE]`
**Expect:** time (h:mm) and date ("Monday, October 20"), a greeting ("Good morning" 5–11, "Good afternoon" 12–16, "Good evening" otherwise; with a name once the family sets one), the photo card with "Welcome to your new home screen.", six tiles Phone, Messages, Camera, Photos, Reminders, Help, the shield chip, and "All my apps". Screenshot in light and dark, at app font scale 1.3 (default) and with system font scale 2.0.

### GM-HOME-002 · Every default tile opens the right thing  `P0`
**Expect:** Phone → the dialer; Messages → the SMS app; Camera → the camera; Photos → "Family photos" (Guardian's own page); Reminders → "Today's reminders"; Help → "Ask your family for help" (see GM-HELP). Back from each returns to Guardian's home. Record anything that does nothing (for example Messages on an emulator image without an SMS app: it should fall back, not silently fail).

### GM-HOME-003 · Call tile, under 5 seconds  `P0` `[AVD]` `[PHONE]` `[HUMAN]`
**Setup:** inject the family state (on the phone, use Nate's number for Sarah).
**Steps:** from the lock screen, unlock and tap "Sarah". Time it from screen-on to the call ringing out. On the phone, ask Nate to have someone who hasn't seen the app try it.
**Expect:** the call starts straight away (CALL_PHONE granted), no confirmation, under 5 seconds total. TalkBack reads the tile as "Call Sarah". Without CALL_PHONE (revoke it), the dialer opens with the number filled in.

### GM-HOME-004 · Tiles from the family's config  `P1`
**Setup:** family state.
**Expect:** tiles in config order: Sarah, David, Dr. Patel (a contact), Phone, Messages, Camera, Photos, Reminders, Notes (an app tile, with the app's real icon), Help. Person tiles show a photo or initials. A tile whose target doesn't exist (edit state: a CALL_CONTACT with target `nobody`, an APP tile for a package that isn't installed) is left out, not shown broken.

### GM-HOME-005 · Columns  `P2`
**Steps:** set `home.columns` to 1, 3 and 5 in state.
**Expect:** 1 and 3 columns lay out cleanly; 5 is clamped to 3. Labels never cut mid-word at 3 columns with font scale 2.0; if they do, report the worst case with a screenshot.

### GM-HOME-006 · Shield chip states  `P0`
**Expect:** "Protected" (green) when everything the parent agreed to is granted; "Check me" when something is missing (revoke Usage access); "Paused" during "Pause everything". The chip's TalkBack label is "<state>. What my family can see." Tapping it opens the transparency screen in one tap (spec bar 5).

### GM-HOME-007 · Next reminder strip  `P1`
**Setup:** family state, with a reminder time 90 minutes from now, then 3 hours from now.
**Expect:** within 2 hours: a strip "Coming up at h:mm AM/PM" with the title; tapping it opens "Today's reminders". Beyond 2 hours: no strip.

### GM-HOME-008 · The clock keeps time  `P2`
**Expect:** the time on screen updates within about 10 s of the minute changing. After changing the time zone, the clock and date follow it.

### GM-HOME-009 · Home button behavior  `P1`
**Steps:** open "What my family can see", then press Home. Open "All my apps", press Home. Open Chase, press Home.
**Expect:** every time, Guardian's main home screen (not the sub-page you were on).

### GM-HOME-010 · All my apps  `P1`
**Expect:** solo default: "Your family hasn't added any apps yet." plus a "Phone settings" row. With the family state: Notes as a big row, alphabetical, and "Phone settings". Tapping Notes opens it. At Guided/Locked the list needs the code (see 03).

### GM-HOME-011 · Recents and the Guardian task  `P2`
**Expect:** Guardian's home doesn't appear in Recents as a card. The full-screen cards and Help don't pile up in Recents either.

### GM-HOME-012 · Portrait only  `P2`
**Steps:** turn auto-rotate on and rotate the emulator.
**Expect:** Guardian stays in portrait on every screen. Other apps rotate normally.

### GM-HOME-013 · Pending helper card  `P1` `[B]`
Covered in GM-FAM-020.

### GM-HOME-014 · Long content  `P2`
**Steps:** set `parentName` to a 40-character name, a tile label of 30 characters, a reminder title of 80 characters.
**Expect:** nothing overlaps or runs off the screen at 360 dp wide with font scale 2.0. Text wraps or ellipsizes cleanly.

### GM-HOME-015 · Switching away and back to the home app  `P1`
**Steps:** in Settings > Apps > Default apps, pick the stock launcher. Then switch back to Guardian.
**Expect:** with the stock launcher as home, Guardian's shield chip (when opened from the drawer) says "Check me". Switching back restores everything with no re-setup.
