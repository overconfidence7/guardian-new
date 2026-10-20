# 19 · Scam scenarios, end to end

Role-play the real thing. Each scenario is a script a scammer would follow, run start to finish without resetting in between, the way a parent would live it. Run each on `gm-api36` (you play both sides with `adb emu gsm`) and, where tagged, on the real phone with Nate playing the scammer from his own phone. Nate's number isn't in the solo config, so it counts as unknown: exactly what we want.

For each run, record a timeline: what the "scammer" said, what the parent tapped, what Guardian showed (screenshot), what was recorded (state JSON), and in Track B what the family got and when. Then answer: **would this have stopped or slowed a real scam? Where did it fail?** That judgment is the most valuable part of this file.

Run each scenario at Simple and at Locked.

### GM-SCAM-001 · "Microsoft support"  `P0` `[AVD]` `[PHONE]` `[HUMAN]`
The core story from `docs/SPEC.md` §4.
1. 10:02 unknown number calls. (Expect: silenced.) The parent answers anyway.
2. 10:05 "Your computer has a virus, install AnyDesk so I can fix it." Install the AnyDesk stand-in during the call. (Expect: full-screen warning at once.)
3. The parent taps "I trust this app". "Now open AnyDesk and read me the number." Open it. (Expect: no second warning within 10 minutes; record.)
4. "To refund you, open your bank app." Open Chase. (Expect: pause card with "It's OK to hang up.")
5. "Tap continue, it's fine." Tap "I'm fine, continue".
6. "Now open Zelle." (Expect: a card: payment is a different category from bank.)
7. Keep the call past 20 minutes. (Expect: an `URGENT` long-call event, because risky apps were opened.)
8. Hang up. (Expect: the safe-word card.)
**Track B extra:** Sarah's dashboard and the Functions log: list every message she got, with times. Would she have known to call within the first five minutes?

### GM-SCAM-002 · "Grandma, it's me, I'm in trouble"  `P0` `[AVD]` `[PHONE]` `[HUMAN]`
1. Unknown number calls; a "grandson" says he's in jail and needs bail, don't tell Mom and Dad.
2. "Buy Target gift cards and read me the numbers." During the call, open the Target stand-in. (Expect: card, gift-card wording "never ask you to buy gift cards".)
3. Hang up after 2 minutes. (Expect: safe-word card. Tap "Show our safe word". Tap "Call David".)
**Judge:** does the safe-word card arrive in time to matter, or only after the damage? Would it help to show it during the call?

### GM-SCAM-003 · "This is your bank's fraud department"  `P0` `[AVD]`
1. Unknown call: "There's fraud on your account. Move your money to a safe account with Zelle." Open Zelle during the call. (Expect: card.)
2. "Hang up and I'll call you right back from the bank's number." Hang up; the caller calls again from a different unknown number after 5 minutes (still inside 30 minutes). Open Chase. (Expect: card; a new call re-arms everything.)
3. Variant: "Call us back at 555-999-8888." The parent calls out. Open Chase. (Expect today: no card if the earlier window has passed, suspect S-14. Record.)

### GM-SCAM-004 · "Social Security will suspend your number"  `P1` `[AVD]`
1. Unknown call, 25 minutes of pressure. Open Coinbase at minute 22. (Expect: card with "send crypto or Bitcoin", long-call event.)
2. The parent keeps the phone face down (screen off) most of the call. (Expect: the long-call event still lands near minute 20.)

### GM-SCAM-005 · The scammer tries to switch Guardian off  `P0` `[AVD]` `[PHONE]` `[HUMAN]`
During an unknown call, the scammer coaches the parent through each of these. For each, at each lock level, record whether it worked, whether the code was needed, and whether anyone was told (Track B):
1. "Pull down from the top of the screen and tap Pause for 1 hour." (Suspect S-03.)
2. "Tap the shield at the top, then tap Pause everything."
3. "Tap the shield, turn off Pause before sending money."
4. "Tap the shield, scroll to the bottom, tap Stop using Guardian Mode." (Needs the code at every level; what if the parent reads the code out to the caller?)
5. "Open Settings, Apps, Guardian Mode, Force stop." (Covered at Locked; at Simple and Guided it works. How long until protection is back?)
6. "Open Settings and turn off Display over other apps / Usage access for Guardian."
7. "Pull down Quick Settings and tap Active apps, then Stop."
8. "Turn on airplane mode." (Protection still works on the phone; the family stops hearing anything.)
9. "Hold the power button, tap and hold Power off, restart in safe mode." (Guardian can't stop this, by design. Record what the parent sees.)
**Judge:** list the steps a scammer can complete in under a minute without the code, ranked by how much protection they remove. This list is the most important output of the scenarios.

### GM-SCAM-006 · An app that isn't on the list  `P1` `[AVD]`
1. "Install Screen Helper from this link." Install the Screen Helper stand-in during an unknown call. (Expect: the milder warning, `URGENT` because of the call.)
2. "Now go to Settings, Accessibility, and turn on Screen Helper." (Expect on Android 16: Android blocks turning on accessibility during a call with a non-contact. On older versions it works; record. At Locked, Settings is covered.)

### GM-SCAM-007 · The scammer becomes a "helper"  `P0` `[B]`
1. On the dashboard, a "scammer" account (`help@support-desk.example`) creates its own family and gets a setup code.
2. During an unknown call: "Tap the shield, Stop using Guardian Mode, enter your code." The parent does.
3. "Now open Guardian again, Set up with my family, type this code." The parent types the scammer's code and taps "Yes, Support Desk can help".
**Expect:** record exactly what protections stand in the way and what Sarah is told and when ("this phone has left the family"). Afterwards, the phone's "Call ___ first" dials the scammer's number. This is a real attack path; the report should say how quickly Sarah would know, and suggest what could slow it (for example a cooling-off period before re-pairing, or blocking "Stop using" during or right after an unknown call).

### GM-SCAM-008 · Calling twice to get through  `P2` `[AVD]`
Scammers learn the rules. Unknown number calls, silenced; calls again within 3 minutes: rings (the "call twice if urgent" rule). **Judge:** is that rule worth it? Record how many real scams use repeat calls (from your knowledge; mark it as general knowledge, not tested).

### GM-SCAM-009 · WhatsApp or other internet calls  `P1`
Android's call state doesn't cover VoIP apps, so a WhatsApp call never arms anything (`docs/POLICY.md` §2). Can't be tested without accounts; confirm on the real phone only if Nate wants to, with his own WhatsApp. Otherwise mark SKIP and keep it in the report's "not covered" list, because many scams now come by WhatsApp.

### GM-SCAM-010 · Your own scenario  `P1`
Invent at least two more from current scam reports you know about (romance scams that run for weeks, fake package delivery texts that lead to a call, "your grandson's lawyer" follow-ups, QR codes on parking meters). Run them and write them up like the ones above.
