# 08 · Unknown-caller screening

`docs/SPEC.md` F6, `core/CallScreeningPolicy.kt`, `protection/GuardianCallScreeningService.kt`.

**Modes:** Off (everything rings), Silence unknown callers (the default: rings silently, shows on screen, goes to voicemail if not answered, stays in the call log), Send unknown callers to voicemail (declined, no missed-call notification, stays in the call log). The parent's switch on the transparency screen toggles between Silence and Off; Voicemail is set from the dashboard (Track B).

**Always ring:** the phone's contacts, Guardian's family and contacts, the allowlist. A second call from the same unknown number within 3 minutes rings. Calls whose caller ID fails network verification never ring. If Guardian has a problem, the call rings (fail open).

**How to tell "silenced" from "ringing" on an emulator:** watch `adb shell dumpsys telecom` during the call (look at the call's state and any "silence"/"ringing" fields, and `Ringer` / `mIsRinging`), and `adb shell dumpsys audio` for the ring stream being active. On the real phone, listen. Record the exact fields you used so later runs can reuse them.

**Setup:** fast setup, Guardian holds the call-screening role. Family state injected for the known-number cases.

### GM-SCRN-001 · Unknown caller is silenced  `P0` `[AVD]` `[PHONE]`
**Steps:** `adb emu gsm call 5559998888`.
**Expect:** the incoming-call screen shows, but no ringtone or vibration. Leave it unanswered until it stops: it goes to voicemail / missed, and the call appears in the dialer's call log. Logcat `GuardianScreening` shows no error.

### GM-SCRN-002 · Family rings  `P0`
**Expect:** a call from Sarah (`5550101111`) rings normally. Repeat with the number stored in a different format in config (`+1 (555) 010-1111`).

### GM-SCRN-003 · Phone contacts ring  `P0`
**Steps:** save `5550107777` in Contacts. Call from it with READ_CONTACTS granted, then revoked.
**Expect:** rings both times (without the permission, Android doesn't ask Guardian about contacts at all).

### GM-SCRN-004 · Guardian contact and allowlist ring  `P1`
**Expect:** Dr. Patel (`5550103333`, a Guardian contact) and Pharmacy (`5550104444`, allowlisted) ring.

### GM-SCRN-005 · Second call within 3 minutes rings  `P0`
**Steps:** unknown `5559997777` calls and stops (silenced). Within 2 minutes it calls again. Then wait 4 minutes and it calls a third time.
**Expect:** first silenced, second rings, third silenced again. Also: three quick calls in a row, record which ring.

### GM-SCRN-006 · Screening off  `P1`
**Steps:** transparency screen, turn "Unknown caller screening" off.
**Expect:** unknown calls ring. The transparency item says Off. Turning it back on restores silencing (mode `SILENCE_UNKNOWN` in config; if it was Voicemail before, record that it comes back as Silence, and say whether that's surprising).

### GM-SCRN-007 · Voicemail mode  `P1` `[B]`
**Steps:** from the dashboard, set "Send unknown callers to voicemail".
**Expect:** unknown calls never ring or show; no missed-call notification; the call is in the call log. Family still rings.

### GM-SCRN-008 · "Pause everything" lets every call ring  `P2`
**Expect:** while paused, unknown calls ring normally (suspect S-18). Note whether the parent is told this when pausing.

### GM-SCRN-009 · Screening role taken away  `P1`
**Steps:** remove the role with adb (or pick another caller ID app in Settings).
**Expect:** calls ring as usual. Home chip "Check me". See GM-PAUSE-009 for what this does to pause cards.

### GM-SCRN-010 · Outgoing calls are untouched  `P0`
**Expect:** calls the parent places (to anyone, including unknown numbers and 911 on the real phone's emergency dialer screen only, never actually placing it) are never blocked or delayed.

### GM-SCRN-011 · Screening is fast  `P1`
**Steps:** time from `gsm call` to the incoming-call screen with and without Guardian holding the role, ten calls each on `gm-api29-budget`.
**Expect:** Guardian adds no noticeable delay (well under a second). Android gives screening apps 5 seconds; any timeout in logcat is a P0.

### GM-SCRN-012 · The number is never stored or sent  `P0`
**Steps:** after several screened calls, grep the state JSON, every file under the app's data dir, and logcat for `5559998888` (all formats: `5559998888`, `555-999-8888`, `9998888`).
**Expect:** not found anywhere. (Screening uses the number only to decide.)

### GM-SCRN-013 · Hidden and unusual numbers  `P1` `[PHONE]` `[HUMAN]`
**Steps:** with Nate: a call with caller ID hidden; a call from a short code if he has one; an international number if available.
**Expect:** hidden numbers aren't screened by Android (they ring); record what happens. Nothing crashes.

### GM-SCRN-014 · Spoofed caller ID  `P2` `[PHONE]`
**Expect:** can't be simulated on an emulator. Note in the report that it's untested and why; unit tests cover the rule (`CallScreeningPolicyTest`).

### GM-SCRN-015 · Wording matches what happens  `P2`
Compare the consent page ("Calls from numbers you don't know won't ring out loud. They can still leave a voicemail. Family, your contacts and any numbers your family adds always ring.") and the transparency item with what you saw. Flag anything the parent was told that isn't true (for example the "call twice if urgent" rule isn't mentioned to the parent anywhere; suggest whether it should be).
