# 02 · Setup and consent

Run this file by hand, tapping through Android's real permission screens, on `gm-api36`, `gm-api29-budget`, `gm-api36-play` and the real phone. This is the one place where "fast setup" with adb is not allowed: the point is the parent's experience.

Path: Role → "Make this phone simpler" → "Set up on my own for now" → six consent pages → lock level → lock code → Done.

The six pages, in order (`ui/onboarding/ConsentPages.kt`):

| # | Title | Asks Android for |
|---|---|---|
| 1 | A simpler home screen | Home app role, Phone calls (CALL_PHONE) |
| 2 | Pause before sending money | Usage access, Display over other apps |
| 3 | Quiet unknown callers | Call screening role, Phone state, Contacts |
| 4 | A daily "I'm OK" | nothing |
| 5 | Reminders | Notifications, Alarms and reminders, Full-screen notifications |
| 6 | Keep Guardian awake | Ignore battery optimization |

### GM-CONS-001 · Every page says what's shared and what isn't, before any Android prompt  `P0`
**Steps:** on each page, before tapping anything, screenshot.
**Expect:** "Step N of 6" with a progress bar, the title, the intro, a box "Your family will see" and a box "Never shared". No Android permission dialog has appeared yet on any page. Text matches `ConsentPages.kt`. This is Play's prominent-disclosure requirement (`docs/POLICY.md`).

### GM-CONS-002 · "Turn on" then a checklist, one Android screen at a time  `P0`
**Steps:** page 1, "Turn on".
**Expect:** a line "Android will ask you to allow a few things.", a checklist with an empty circle next to each thing still needed ("Guardian is the home screen", "Can call family with one tap"), an "On the next screen" box with the plain instruction for the first missing item, a "Continue" button and a "Skip for now" text button. Android's own screen opens for the first missing item.

### GM-CONS-003 · Home app role  `P0`
**Steps:** on Android's home app chooser, pick Guardian Mode and confirm.
**Expect:** back in setup, "Guardian is the home screen" is ticked. The instruction shown before was "Choose Guardian Mode, then tap "Set as default"." Check that matches what Android actually shows on each API level; if Android's wording is different (it often is), report the difference and the real wording per device.

### GM-CONS-004 · Phone calls permission  `P0`
**Expect:** Android's "Allow Guardian Mode to make and manage phone calls?" dialog. After Allow, the checklist ticks it, all items done, "All set" and a "Next" button.

### GM-CONS-005 · Usage access deep link lands on Guardian's switch  `P0`
**Steps:** page 2, Turn on, Continue.
**Expect:** Settings opens on Usage access, ideally straight on Guardian's entry. Turn it on, press Back: you're back in setup with it ticked. Record per device whether it opened the list or Guardian's own switch, and how many taps it took. If Settings refuses ("restricted setting"), see GM-CONS-012.

### GM-CONS-006 · Display over other apps  `P0`
**Expect:** Settings opens on Guardian's "Display over other apps" switch. After turning it on and pressing Back, it's ticked.

### GM-CONS-007 · Call screening role, phone state and contacts  `P0`
**Steps:** page 3. Grant all three, one at a time.
**Expect:** the screening role dialog says something like "Set Guardian Mode as your default caller ID & spam app?" (record the real wording). Then the phone-state and contacts dialogs. All three ticked.

### GM-CONS-008 · Daily check-in page has no Android prompt  `P1`
**Expect:** page 4, "Turn on" goes straight to "All set" and Next. "Not now" moves on. No Android dialog either way.

### GM-CONS-009 · Reminders: notifications, alarms, full screen  `P0`
**Steps:** page 5. On API 33+ the notification dialog; on API 31+ the "Alarms & reminders" switch; on API 34+ the "Full-screen notifications" switch.
**Expect:** each opens the right screen for Guardian, and comes back ticked. On API 29, notifications and alarms are ticked already and only what's needed is asked.

### GM-CONS-010 · Battery page  `P1`
**Expect:** Android's "Let app always run in background?" dialog. After Allow, ticked. On the real phone (likely Samsung or Motorola), note whether the OEM has its own extra battery screen Guardian doesn't mention (Samsung "Never sleeping apps", Motorola "Battery optimization"). Report what extra steps a parent would need.

### GM-CONS-011 · "Not now" and "Skip for now" really skip  `P0`
**Steps:** fresh install. Page 1 "Turn on", then "Skip for now" without granting. Page 2 "Not now". Page 3 "Turn on" then grant only Phone state, then "Skip for now". Finish setup.
**Expect:** setup completes. No permission is granted that the parent didn't allow (`adb shell dumpsys package app.guardianmode.debug | Select-String granted=true`, `appops get app.guardianmode.debug`). Saved `consent` (state JSON) shows `home: true`, `pause: false`, `calls: true`. After setup, the home screen shield chip says "Check me" (something the parent agreed to is missing). Nothing nags with a dialog.

### GM-CONS-012 · Restricted settings on the Play image and real phone  `P0` `[PHONE]`
**Steps:** on `gm-api36-play` (sideloaded APK), page 2, Continue to Usage access.
**Expect:** Android may grey out the switch with "Restricted setting". Record exactly what the parent sees. Then follow `docs/TESTING.md` "Install on a real phone" step 3 (App info > ⋮ > "Allow restricted settings") and check it works. Report: does Guardian's own screen give any hint of what to do? (It doesn't today, as far as the code shows; a sideloading tester will get stuck here. Suggest wording.)

### GM-CONS-013 · Denying a runtime permission twice  `P1`
**Steps:** page 1, Turn on, deny "make and manage phone calls" twice (Android stops asking after the second deny).
**Expect:** setup doesn't loop. Continue now does something sensible (opens App info, or nothing). Record what happens and whether the parent can still finish.

### GM-CONS-014 · Back inside the consent pages  `P1`
**Steps:** on page 4, press Back three times; then go forward again.
**Expect:** pages 3, 2, 1 in order, each showing its earlier answer (an accepted page shows its checklist or "All set", not "Turn on"). Back from page 1 goes to Welcome (solo). Going forward keeps answers.

### GM-CONS-015 · Leaving setup half way and coming back  `P1`
**Steps:** on page 3, press Home (Guardian may already be the home app after page 1, so you may land back in Guardian; record what happens). Open Settings, come back.
**Expect:** setup resumes at the same page or restarts cleanly. Guardian never shows a half-working home screen before setup is done. If pressing Home while Guardian is the home app but setup isn't done traps you in setup with no way out, that's a P0 (the parent can't use their phone).

### GM-CONS-016 · Revisiting permissions after setup  `P1`
**Steps:** after setup, revoke Usage access with adb, return to the home screen.
**Expect:** shield chip "Check me". Tapping the chip opens "What my family can see". Is there any way for the parent to fix the missing permission from there? Record. (The spec says every consent screen is reachable later from the transparency screen, `docs/POLICY.md` "Prominent disclosure"; if it isn't, that's a P1 bug.)

### GM-CONS-017 · Consent wording reads naturally  `P2`
Read every consent page out loud as if to a 75-year-old. Flag anything confusing, jargon ("overlay", "role", "optimization"), or anything that sounds written by a machine. Suggest better wording in the report.
