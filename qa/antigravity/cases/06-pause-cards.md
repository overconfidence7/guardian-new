# 06 · Pause cards

The heart of the app (`docs/SPEC.md` F4, bars 3 and 4; `core/TripwireEngine.kt`).

**The rule:** a bank, payment, gift-card or crypto app coming to the front **during an answered call with an unknown number**, or **within 30 minutes after one ends**, gets a full-screen card within 2 seconds. Calls from family, Guardian contacts, the phone's contacts and the allowlist never arm it. The card shows once per app category per call. "I'm fine, continue" lets that app through until the next unknown call is answered.

**The card** (`ui/cards/CardContent.kt`, PauseCard): "Hold on a minute." / "Did someone on the phone ask you to open Chase?" / a callout that starts with "It's OK to hang up." during a call, then "Real banks, police, Medicare and tech companies never ask you to …" with the category's words (bank: "move money from your bank"; payment: "send money"; gift card: "buy gift cards"; crypto: "send crypto or Bitcoin"). Buttons: "Call Sarah first" (only when a family member with a number exists), "I'm fine, continue", and "Go to my home screen".

**Setup for this file:** `gm-api36` and `gm-api29-budget`, fast setup with every permission (Usage access and overlay are what matter), Guardian is the home app and the call-screening app. Install the Chase, Venmo, Zelle, Target, Coinbase and Notes stand-ins. Run once in solo default state and once with `templates/state-family.json` injected. Unknown caller: `5559998888`. Family: `5550101111` (Sarah in the family state).

**A standard unknown call** (used below):
```powershell
adb emu gsm call 5559998888; Start-Sleep 3; adb emu gsm accept 5559998888
Start-Sleep 40      # talk for 40 s (over 30 s, so the safe-word card follows)
adb emu gsm cancel 5559998888
```
A safe-word card appears after the call; tap "Everything's fine" before going on, unless the case says otherwise.

For every card that appears: screenshot, record the delay from the app opening to the card (screen recording, frame count), and pull the state JSON to check `sharedEvents` and `outbox`.

## Arming

### GM-PAUSE-001 · Bank app right after an unknown call  `P0` `[AVD]` `[PHONE]`
**Steps:** standard unknown call. Dismiss the safe-word card. Open Chase from All my apps or with `monkey`.
**Expect:** the pause card within 2 seconds, Chase hidden behind it. No "It's OK to hang up." (the call is over). `sharedEvents` gets `PAUSE_SHOWN` with `severity: NOTICE`, `data.app: "Chase"`, `category: "BANK"`, `duringCall: "false"`, `minutesSinceCall: "0"`.

### GM-PAUSE-002 · Bank app during the call  `P0` `[AVD]`
**Steps:** start and accept the unknown call, keep it going, open Chase.
**Expect:** card within 2 seconds with "It's OK to hang up. Real banks, police, Medicare and tech companies never ask you to move money from your bank." Event severity `URGENT`, `duringCall: "true"`. The call keeps going underneath (check `adb emu gsm list`), and the in-call notification / green chip is still there to get back to the call.

### GM-PAUSE-003 · Every category  `P0`
**Steps:** for each of Venmo, Zelle (payment), Target (gift card), Coinbase (crypto), do a fresh unknown call, then open the app.
**Expect:** a card each time with the right category words, and the right `category` in the event.

### GM-PAUSE-004 · The 30-minute window  `P0`
**Steps:** standard unknown call. Move the clock 29 minutes forward, open Chase. New call; move the clock 31 minutes forward, open Chase. Do the 29/31 pair once for real (no clock change) on `gm-api36`.
**Expect:** at 29 minutes, a card, with `minutesSinceCall: "29"`. At 31 minutes, no card.

### GM-PAUSE-005 · An unknown call that isn't answered doesn't arm  `P0`
**Steps:** `adb emu gsm call 5559998888`, wait 20 s (it rings silently), `adb emu gsm cancel`. Open Chase.
**Expect:** no card. (By design: a missed call isn't a conversation.) Record it; also note in the report whether a silenced-then-missed call followed by the parent calling back (GM-PAUSE-011) is covered.

### GM-PAUSE-006 · A family call never arms  `P0` `[AVD]` `[PHONE]`
**Setup:** family state.
**Steps:** call from `5550101111` (Sarah), accept, talk 40 s, hang up. Open Chase. Also during the call.
**Expect:** no card, no event, no safe-word card. Repeat with `+15550101111`, `15550101111` and `(555) 010-1111` written in the family config, to check number matching ignores formatting.

### GM-PAUSE-007 · A phone contact never arms  `P0`
**Steps:** save `5550107777` as "Pat" in the phone's Contacts. Call from it, accept, hang up, open Chase.
**Expect:** no card. Then revoke READ_CONTACTS and repeat: record the result (see suspect S-13; Android doesn't ask the screening app about contacts when Guardian can't read them, so it should still count as known).

### GM-PAUSE-008 · An allowlisted number never arms  `P1`
**Setup:** family state (Pharmacy `+1 555 010 4444` is allowlisted).
**Expect:** a call from `5550104444` doesn't arm.

### GM-PAUSE-009 · Screening role and contacts combinations  `P0`
See suspect S-13. For each combination, do one Sarah call and one unknown call, then open Chase after each:

| Screening role | Contacts | Unknown call should | Sarah call should |
|---|---|---|---|
| held | granted | arm | not arm |
| held | denied | arm | not arm |
| not held | granted | ? | ? |
| not held | denied | ? | ? |

**Expect:** the first two rows as shown. Record what actually happens in the last two and explain it in the report. A Sarah call arming the card in any row breaks spec bar 4 (P0). An unknown call never arming in a setup the parent can reach by tapping "Skip for now" is a P0 too, unless the transparency screen tells them pause cards won't work.

### GM-PAUSE-010 · Hidden numbers  `P1` `[PHONE]` `[HUMAN]`
**Steps:** ask Nate to call the test phone with his number hidden (`*67` + number in the US), the phone answers, talks 40 s, hangs up, opens a real or stand-in bank app (with Nate's OK about installing a stand-in).
**Expect:** treated as unknown: the card appears.

### GM-PAUSE-011 · Calling the scammer back  `P1`
**Steps:** the parent places a call to `5559998888` (`am start -a android.intent.action.CALL -d tel:5559998888`), on the emulator answer from the console isn't needed: the call connects. Talk 40 s, hang up. Open Chase.
**Expect:** today, no card (suspect S-14). Record it and explain the risk.

### GM-PAUSE-012 · A second call during the unknown one  `P2`
**Steps:** during an answered unknown call, Sarah calls (call waiting: `adb emu gsm call 5550101111`). Ignore it, then hang up both. Open Chase.
**Expect:** still armed (the unknown call happened). No crash.

## Once per call, and "continue"

### GM-PAUSE-020 · "I'm fine, continue"  `P0`
**Steps:** get the card for Chase, tap "I'm fine, continue".
**Expect:** the card closes and Chase is in front. Event `PAUSE_CONTINUED` with `app: "Chase"`. Open Chase again (switch to Notes and back): no second card. Open Venmo: a card (different category).

### GM-PAUSE-021 · "Call Sarah first"  `P0`
**Setup:** family state.
**Steps:** get the card after a call, tap "Call Sarah first".
**Expect:** the phone calls Sarah's number straight away (`adb emu gsm list` shows an outbound call to 5550101111), the card closes, event `PAUSE_CALLED_FAMILY`. During an active unknown call, tapping it: record what Android does (hold and dial, or fail). It must not silently do nothing.

### GM-PAUSE-022 · "Go to my home screen"  `P1`
**Expect:** Guardian's home screen; no event. Open Chase again in the same call: record whether a card appears (suspect S-15).

### GM-PAUSE-023 · Once per category per call  `P1`
**Steps:** after a standard unknown call, open Venmo, get the card, "I'm fine, continue". Open Zelle (also a payment app). Then open Chase (a bank).
**Expect:** no card for Zelle (payment apps were already asked about for this call); a card for Chase. Record it, and say in the report whether a second payment app slipping through is the right call for a parent.

### GM-PAUSE-024 · A new unknown call re-arms everything  `P0`
**Steps:** after "continue" on Chase, do another standard unknown call, open Chase.
**Expect:** a card again.

### GM-PAUSE-025 · Back button on the card  `P1`
**Expect:** Back closes the card and returns to Chase underneath (or home; record). It must not crash or leave an invisible card.

## Switches and pausing

### GM-PAUSE-030 · Pause cards switched off  `P0`
**Steps:** "What my family can see" > "Pause before sending money" off. Standard call, open Chase.
**Expect:** no card, no event. The remote-control alarm still works (open AnyDesk stand-in: warning appears). Turn it back on and it works again.

### GM-PAUSE-031 · "Pause everything for 1 hour"  `P0`
**Steps:** pause everything, standard call, open Chase and AnyDesk.
**Expect:** nothing appears and nothing is recorded in `sharedEvents`. Resume ("Turn protection back on"), new call, open Chase: card.

### GM-PAUSE-032 · Notification "Pause for 1 hour" at Guided/Locked  `P0`
Suspect S-03. **Steps:** level Guided, locked. Pull down the shade and tap "Pause for 1 hour" on Guardian's notification. Then standard call and open Chase.
**Expect (per the lock rules):** the code is needed. Record what actually happens.

## Speed, delivery and odd states

### GM-PAUSE-040 · Under 2 seconds, on the budget emulator  `P0`
**Steps:** on `gm-api29-budget`, run GM-PAUSE-001 ten times with a screen recording. Also with the screen just turned on (call ends, screen off, then the parent unlocks and opens Chase).
**Expect:** card within 2 s every time. Report min / median / max. Any time Chase is usable for longer is a P0.

### GM-PAUSE-041 · Without "Display over other apps"  `P1`
**Steps:** deny overlay (`appops set ... SYSTEM_ALERT_WINDOW deny`), keep Guardian as home. Then also remove the home role.
**Expect:** with the home role: the card still appears (home apps may start activities). Without either: a high-priority notification "Hold on a minute" / "Did someone on the phone ask you to open Chase? Tap to talk it over with family first." that opens the card when tapped. Record exactly what the parent sees, and whether it's full screen (needs the full-screen permission) or a banner.

### GM-PAUSE-042 · Process killed after the call  `P0`
**Steps:** standard unknown call. Then `adb shell am kill app.guardianmode.debug` (it's in the background behind Notes), wait 5 s, open Chase. Repeat with `am force-stop` and with the emulator's low-memory killer (`adb shell am send-trim-memory app.guardianmode.debug RUNNING_CRITICAL`, then open heavy apps).
**Expect (per spec):** the card still appears within the 30 minutes. The rule state lives in memory (`TripwireEngine`), so a killed process may forget the call. Record what happens in each case; if a killed process means no card, it's a P0 on budget phones where Android kills background apps often.

### GM-PAUSE-043 · Reboot after the call  `P1`
**Steps:** standard call, reboot the emulator, unlock, open Chase within the window.
**Expect:** record. (Same memory issue as 042.)

### GM-PAUSE-044 · Usage access revoked  `P0`
**Steps:** revoke Usage access, standard call, open Chase.
**Expect:** no card (can't see apps). The home chip says "Check me". Record whether anything else tells the parent or family (in Track B, the dashboard should show "Needs attention").

### GM-PAUSE-045 · The card in dark mode, high contrast and large text  `P2`
**Expect:** readable in each, all three buttons visible without scrolling at font scale 1.3 on a 360 dp screen, and reachable by scrolling at 2.0.

### GM-PAUSE-046 · Two risky apps at once  `P2`
**Steps:** armed; open Chase; while the card is up, use Recents to open Coinbase.
**Expect:** a Coinbase card replaces or follows the Chase one, no crash, and after dealing with both, Back leaves no card behind.

### GM-PAUSE-047 · An app switch Android records a moment late  `P0`
**Steps:** standard call, then open Chase while the phone is busy (CI emulator, or a budget phone with heavy apps open).
**Expect:** the card, every time. Android stamps an app switch with the time it happened but stores it a little later; Guardian must still see a switch stamped before its previous look.
