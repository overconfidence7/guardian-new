# 10 · "What my family can see" and pausing

`docs/SPEC.md` F12 and bar 5, `core/Transparency.kt`, `ui/transparency/TransparencyScreen.kt`. The parent must always be able to answer "what does my family see?" in one tap, and the answer must come from the live settings, not a fixed text.

### GM-TRAN-001 · One tap from home  `P0`
**Expect:** tapping the shield chip opens "What my family can see" with the intro "Guardian Mode shares a few safety alerts with the people you chose. It never shares your messages, what's said on calls, or where you are. You can turn any of it off."

### GM-TRAN-002 · Sections in order  `P1`
**Expect:** the pause button, "Your helpers", "What is shared", "Never shared", "Alerts your family received", "Recent changes by your family" (only when there are some), "Phone lock", "Stop using Guardian Mode". Solo: "Your helpers" says "No one yet."

### GM-TRAN-003 · Each feature line matches the live settings  `P0`
**Expect:** seven items: Pause before sending money, Remote-control app alarm, Long calls with unknown numbers, Unknown caller screening, Daily "I'm OK", Help me button, Reminders. Each with "Shared: …" and "Never shared: …" text from `core/Transparency.kt`, and On/Off. Change settings (switches here; state injection for minutes, check-in deadline and reminders) and check the text follows: the long-call minutes, the check-in deadline time, Reminders shared text changes when any reminder has `notifyFamilyIfMissed: true`, and Reminders shows Off when there are no reminders.

### GM-TRAN-004 · Switches work and stick  `P0`
**Steps:** at Simple, switch each of the five switchable items (pause, remote-control alarm, long calls, screening, check-in) off and on. Force-stop and reopen between.
**Expect:** the On/Off label and the switch agree; the setting really changes behavior (spot-check pause and screening with a call); it survives restarts. TalkBack reads each switch with its title and "On"/"Off". "Help me button" and "Reminders" have no switch; say whether the parent would expect one.

### GM-TRAN-005 · "Never shared" list  `P1`
**Expect:** "Your messages and texts", "What is said on calls", "Your location", "Your photos, unless you send one with Help", "Your passwords or bank details". Check each claim against what the app actually does in Track B (GM-PRIV-020) and flag any that isn't strictly true.

### GM-TRAN-006 · Alerts list shows the exact text  `P0`
**Steps:** trigger one of each: pause card shown, continue, call family, remote-control install, remote-control open, control-capable install, long call.
**Expect:** newest first, each with a date and time ("EEE MMM d, h:mm a") and the wording from `ui/transparency/EventText.kt`, for example "A pause card was shown when you opened Chase after a call from an unknown number." At most 30 kept (trigger 32 and count). The list must say what the family actually saw (in Track B, compare with the dashboard's Alerts tab and the Functions log, GM-FAM-050).

### GM-TRAN-007 · Solo mode alerts honesty  `P1`
Suspect S-06. In solo mode, after triggering a pause card, the section is titled "Alerts your family received" and lists it, though no family exists. Confirm and suggest wording.

### GM-TRAN-008 · Pause everything for 1 hour  `P0`
**Steps:** tap "Pause everything for 1 hour".
**Expect:** the button becomes "Turn protection back on" with "Paused until h:mm AM/PM" (an hour ahead). Home chip "Paused". Notification "Guardian Mode is paused" / "Protection is paused for now." with a "Turn back on" button. No cards, no warnings, no events, calls ring (GM-PAUSE-031, GM-SCRN-008). "Turn protection back on" restores everything at once.

### GM-TRAN-009 · The pause ends by itself  `P0`
**Steps:** pause, then move the clock 61 minutes forward (and once for real).
**Expect:** protection is back: a standard call and Chase gives a card. Home chip back to "Protected". Check the notification text too (suspect S-05).

### GM-TRAN-010 · Pause needs the code at Guided and Locked  `P0`
**Expect:** in the app, yes (GM-LOCK-020). From the notification: see suspect S-03 and GM-PAUSE-032.

### GM-TRAN-011 · Pausing repeatedly  `P2`
**Steps:** pause, wait 50 minutes, pause again.
**Expect:** record whether the second pause extends to an hour from now. Note that a caller could keep it paused forever by asking for a tap every hour; suggest whether helpers should be told after N pauses (Track B: are they told at all? suspect S-04).

### GM-TRAN-012 · Phone lock section  `P1`
**Expect:** the level's description (see GM-LOCK-060) and "Change the lock" (or "Set up a lock" with no code).

### GM-TRAN-013 · Stop using Guardian Mode  `P0`
**Steps:** tap "Stop using Guardian Mode".
**Expect:** the explanation "You can stop whenever you want. Your helpers will see that this phone has left the family.", then a dialog "Stop using Guardian Mode?" / "Your family will no longer get alerts or be able to help with this phone. You'll then choose a different home screen." with "Stop" and "Keep using it". "Keep using it" changes nothing. "Stop" asks for the code (every level), then GM-LOCK-064.

### GM-TRAN-014 · Leaving Guardian without the code is still possible  `P1`
The spec says the parent can always leave Guardian. With the code forgotten, write down every way out you find (safe mode, Settings at Simple and Guided, adb) and whether a parent could find it. This is for Nate's product decision, not a pass/fail.

### GM-TRAN-015 · Screen capture  `P1`
Suspect S-02. `adb shell screencap` on this screen. Per `docs/SECURITY.md` it should be blocked (black).
