# 01 · Install and first run

Start each case from a fresh install (`adb shell pm clear app.guardianmode.debug` or uninstall and reinstall) unless it says otherwise.

### GM-INST-001 · Installs and opens on every device  `P0` `[AVD]` `[PHONE]`
**Steps:** install the test APK. Open it from the app drawer. It should be labelled "Guardian Mode" (the debug build uses the same label as release).
**Expect:** opens within 3 seconds on `gm-api36`, within 5 on `gm-api29-budget`. No crash dialog. First screen is the role choice (GM-INST-002). Record cold start time with `adb shell am start -W -n app.guardianmode.debug/app.guardianmode.ui.MainActivity` (TotalTime).
**In the cloud:** `device_checks.py` `gm_inst_001_installsAndOpensOnTheRoleChoice` (API 29 and 36) installs without granting anything, opens the launcher entry with `am start -W`, checks the role choice comes up with no crash, and times the first open and a cold start after a force stop (both in the report's measurements; the bar applies to the cold start). `PrivacyDeviceTest.gm_priv_022_gm_inst_001_...` checks the launcher label is "Guardian Mode" on all three levels.

### GM-INST-002 · Role choice screen  `P0` `[AVD]`
**Expect:** a shield icon, the heading "How will this phone be used?", the line "Guardian Mode goes on the phone that should be simpler. Family who help can use it on their own phone too.", a primary button "Make this phone simpler" with "For the person who will use this phone every day. You can set it up for them." under it, and a secondary button "Help someone else's phone" with "For family who help from their own phone." Nothing on screen says "monitor", "track" or "watch". Screenshot at default font size and at system font scale 2.0 (no text cut off; the page scrolls).

### GM-INST-003 · Helper path explains it isn't ready  `P1` `[AVD]`
**Steps:** tap "Help someone else's phone".
**Expect:** heading "Helping from your phone", the explanation, and a highlighted box: "Linking phones isn't turned on in this test version yet. For now, set up their phone with "Make this phone simpler"." A "Back" button returns to the role choice. System Back does the same. Nothing else is reachable from here.

### GM-INST-004 · Back on the role screen  `P2` `[AVD]`
**Steps:** on the role choice, press Back.
**Expect:** the app closes or goes to the previous launcher; it doesn't loop or crash. Reopen it: still the role choice, no half-finished state.

### GM-INST-005 · Welcome screen  `P0` `[AVD]`
**Steps:** "Make this phone simpler".
**Expect:** heading "Make your phone easier to use.", the lead paragraph, a primary "Set up with my family" and a text button "Set up on my own for now". No "Skip" with a guilt-trip, no pre-ticked boxes, no dark patterns.

### GM-INST-006 · "Set up with my family" in the test build fails kindly  `P1` `[AVD]`
**Steps:** "Set up with my family", type `ABC234`, Continue.
**Expect:** a busy spinner, then one of the plain messages ("That code didn't work. Double check it with your family member." / "The phone isn't connected to the internet. Connect to Wi-Fi and try again." / "That didn't work. Try again in a minute."). No raw error, no crash, no endless spinner (time it; anything over 30 s is a bug). Record which message appears; the test build has no backend, so any of them is a pass if it's kind and you can go back.

### GM-INST-007 · Setup code field  `P1` `[AVD]`
**Steps:** on "Enter your setup code": type lowercase `abc234`; type `I`, `O`, `0`, `1`, `L` (not in the code alphabet); paste `ab-c 2 34`; type 8 characters; press the keyboard's Done key with 5 characters.
**Expect:** letters show in capitals, characters outside `ABCDEFGHJKMNPQRSTUVWXYZ23456789` are dropped, separators are stripped, input stops at 6, Done with fewer than 6 does nothing. The field has the TalkBack label "Setup code". The keyboard opens by itself.

### GM-INST-008 · Scan the code instead  `P2` `[AVD]` `[PHONE]`
**Steps:** "Scan the code instead". First deny the camera permission, then go back and allow it.
**Expect:** denied: "Guardian needs the camera to read the code. You can type the code instead." and a "Type the code instead" button that works. Allowed: a live camera preview in a rounded square. On the phone, point it at a QR code containing a setup URL (make one with any QR generator, content `https://example.com/join/ABC234`) and check the code field fills with `ABC234`.

### GM-INST-009 · Back through the first-run screens  `P2` `[AVD]`
**Steps:** go Role → Welcome → Code → Scan, then press Back repeatedly.
**Expect:** Scan → Code → Welcome → Role, then the app closes. The code typed earlier is cleared when you come back to it from Welcome (or kept; record which, either is fine if consistent).

### GM-INST-010 · Process death during first run  `P2` `[AVD]`
**Steps:** at the Code screen, `adb shell am kill app.guardianmode.debug` after pressing Home (the app must be in the background), then reopen.
**Expect:** no crash. Lands on a sensible screen (the role choice is fine). Record where.

### GM-INST-011 · Before setup is finished, Guardian isn't the home app  `P1` `[AVD]`
**Steps:** fresh install, open, stop at the Welcome screen, press the Home button.
**Expect:** the phone's normal launcher appears (Guardian hasn't asked to be the home app yet). Reopening Guardian goes back to setup.
