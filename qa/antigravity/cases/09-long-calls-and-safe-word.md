# 09 · Long calls and the family safe word

`docs/SPEC.md` F7 and F8, `core/TripwireEngine.kt`.

**Long call:** an answered call with an unknown number that passes 20 minutes (family can set 10–60) records one `LONG_UNKNOWN_CALL` event, once per call, with only the minutes and whether it's still going: no number. It's `URGENT` if a money or remote-control app was opened during the call, otherwise `NOTICE`. Nothing is shown to the parent at that moment; it goes to the family (and onto the parent's transparency list).

**Safe word:** after an answered unknown call of 30 seconds or more ends, a gentle card: "Did the caller say they're family?" / "If anyone said they're a grandchild or relative in trouble, ask for your family safe word before doing anything. Your real family will know it." With a word set: "Show our safe word" reveals "Your safe word is "bluebird"". A "Call ___" button for up to two family members with numbers, and "Everything's fine".

### GM-CALL-001 · Long call recorded once, at 20 minutes  `P0` `[AVD]`
**Steps:** accept an unknown call and leave it running. Check the state JSON at 19 and 21 minutes (real time on `gm-api36`; on other devices you may move the clock during the call, but say so).
**Expect:** nothing at 19. By 21: exactly one `LONG_UNKNOWN_CALL` with `data.minutes: "20"`, `ongoing: "true"`, severity `NOTICE`. No phone number or any part of one in the event. At 40 minutes, still only one.

### GM-CALL-002 · A long call that ends between checks  `P1`
**Steps:** move the clock so the call passes 20 minutes, then hang up immediately.
**Expect:** one event recorded at hang-up with `ongoing: "false"` if the tick didn't catch it first. Never two.

### GM-CALL-003 · Urgent when a risky app was opened  `P0`
**Steps:** during an unknown call, open Chase (pause card, continue) or AnyDesk; let the call pass 20 minutes.
**Expect:** the long-call event is `URGENT`.

### GM-CALL-004 · Family and outgoing long calls aren't recorded  `P0`
**Expect:** a 25-minute call with Sarah, or a 25-minute call the parent placed, records nothing.

### GM-CALL-005 · Switched off  `P1`
**Expect:** "Long calls with unknown numbers" off on the transparency screen → no event. The transparency text "That a call with an unknown number went past 20 minutes." shows the family's chosen minutes (Track B: change it to 15 and check the phone says 15).

### GM-CALL-006 · Long call with the screen off  `P1`
**Steps:** start the call, turn the screen off for 25 minutes (`input keyevent 26`), as people do with the phone at their ear.
**Expect:** still recorded around 20 minutes (the service checks every 5 s with the screen off during a call). Record the actual minute it landed.

### GM-CALL-010 · Safe-word card after a 30-second unknown call  `P0` `[AVD]` `[PHONE]`
**Setup:** family state (word "bluebird", Sarah and David with numbers).
**Steps:** standard unknown call (40 s), hang up.
**Expect:** the card appears right after hang-up. "Show our safe word" reveals the word; the word is not shown before tapping. "Call Sarah" and "Call David" dial them. "Everything's fine" closes it. Screenshot before and after reveal (and see suspect S-02 about screen capture).

### GM-CALL-011 · Short or missed calls don't show it  `P1`
**Expect:** an unknown call answered for 20 s: no card. Unanswered: no card. Exactly 30 s: card (move the clock if you need precision; say so).

### GM-CALL-012 · Family calls don't show it  `P0`
**Expect:** a 2-minute call with Sarah: no card.

### GM-CALL-013 · No safe word set  `P1`
**Steps:** solo default (word empty).
**Expect:** the card still shows the advice but no "Show our safe word" button. In solo mode there's no family, so only "Everything's fine". Say in the report whether this card is useful at all in solo mode, or whether it would be better not to show it until a word exists.

### GM-CALL-014 · Safe word switched off  `P1`
**Steps:** there's no switch for it on the transparency screen (only the dashboard has one). Set `protection.safeWord.enabled: false` in state.
**Expect:** no card. Report that the parent can't switch it off themselves, and whether they should be able to.

### GM-CALL-015 · The word never leaves the phone  `P0`
**Expect:** the word appears in no event, no log line, and (Track B) in no Firestore document other than the config the family wrote. Check logcat and state after showing it.

### GM-CALL-016 · Card while the phone is locked  `P1`
**Steps:** standard call, but the screen locks before hang-up (some phones lock during calls with the proximity sensor; simulate with `input keyevent 26` right before `gsm cancel`).
**Expect:** record what the parent sees after unlocking (see suspect S-16).
