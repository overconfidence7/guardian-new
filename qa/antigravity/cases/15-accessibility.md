# 15 · Accessibility

`docs/SPEC.md` bar 8: every touch target at least 64 dp, text starts at 130% of Android's base, WCAG AA contrast (AAA in high-contrast mode), TalkBack labels on everything, no time limits for the parent.

Screens to cover: role choice, welcome, code entry, each consent page, lock level, set code, done, home (solo and family), All my apps, Family photos, Today's reminders, What my family can see, Phone lock, unlock, each card (pause, remote-control, safe word, reminder, locked app), Help (each stage), the share result page.

### GM-A11Y-001 · Touch targets  `P1`
**Steps:** for each screen, `uiautomator dump`, then a small Python script: for every node with `clickable="true"` or `checkable="true"`, convert its bounds to dp (`adb shell wm density`; dp = px × 160 / density) and list any under 64 dp tall or wide (48 dp is Android's minimum; the spec asks for 64).
**Expect:** none under 64 dp on parent-facing screens. List each offender with screen, label and size. Text buttons ("Set up on my own for now", "Skip for now", "Go to my home screen", "I trust this app", dialog buttons) are the likely ones.

### GM-A11Y-002 · TalkBack walk-through  `P0` `[AVD]` `[PHONE]`
**Steps:** turn on TalkBack (Settings > Accessibility, or install from Play on the Play image). Do setup, call Sarah from home, handle a pause card, open the transparency screen and switch a feature off, all by swipe and double-tap only.
**Expect:** every control is reachable and announced with a clear label and role (button, switch, radio, heading). Icons that are decoration aren't announced. The pause card's headline is read first. Record every unlabeled or confusing announcement with the exact words TalkBack said. Note: Guardian must not need Accessibility itself; TalkBack is the parent's own choice.

### GM-A11Y-003 · Big text  `P0`
**Steps:** system font scale 2.0 (`settings put system font_scale 2.0`) plus display size largest, on the 720x1520 budget emulator. Also Guardian's own text size at its maximum (family state `display.fontScale` 2.0 or whatever the dashboard's top step is).
**Expect:** no text clipped or overlapping; long pages scroll; buttons grow with their text; the pause card's buttons are reachable. Screenshot every screen. Record the worst ones.

### GM-A11Y-004 · Contrast  `P1`
**Steps:** sample foreground and background colors from screenshots (light, dark, high contrast) for body text, muted text, buttons, the chip, and the pause card.
**Expect:** at least 4.5:1 for normal text and 3:1 for large text in light and dark; 7:1 in high contrast. List failures with the two hex colors and the ratio.

### GM-A11Y-005 · No time limits  `P1`
**Expect:** no screen times out on the parent: the code-entry and pause cards wait forever; the setup code countdown is on the helper's side only. The 10-minute unlock window is the only timer, and it locks quietly. The lock-code wait is shown as a clock time, not a countdown that requires reading quickly.

### GM-A11Y-006 · Switch Access and keyboard  `P2`
**Steps:** turn on Switch Access or attach a keyboard (`adb shell input keyevent TAB`/`DPAD`).
**Expect:** focus order is sensible top to bottom on home and cards; the focused item is visible.

### GM-A11Y-007 · Color is never the only signal  `P2`
**Expect:** the shield chip says "Protected" / "Check me" / "Paused" in words, not just color; checklist items have icons and "Done" / "Not yet" labels.

### GM-A11Y-008 · Run Google's Accessibility Scanner  `P2` `[AVD]`
**Steps:** on `gm-api36-play`, install Accessibility Scanner from Play (with Nate's OK for the account), scan each screen.
**Expect:** attach the scanner's results and list anything it finds that the above missed.
