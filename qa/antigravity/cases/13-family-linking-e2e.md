# 13 · Family linking, end to end (Track B)

Everything a helper does, on the local stack from RUNBOOK §8: Firebase emulators, the dashboard at http://127.0.0.1:5173, and the Track B debug build on `gm-api36`. Keep the Emulator UI (http://127.0.0.1:4000) open: check Firestore after every step, because what's stored is part of the test.

If pairing fails with a cleartext error, see suspect S-01 first.

Helper wording for every event is in `firebase/functions/src/copy.ts`; the Functions emulator log shows the fake push messages. Use a parent name like "Mom" so the wording reads "Mom opened Chase…".

## Pairing

### GM-FAM-001 · A helper creates the family  `P0` `[B]`
**Steps:** dashboard, "Developer sign-in (emulators only)", `sarah@example.com` / `password123`. On "Welcome, Sarah": family name "The Rivera family", "What do you call your parent?" "Mom", Sarah's phone `+1 555 010 1111`, "Create our family". "Yes, show the code".
**Expect:** a 6-character code from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, a QR code, "Works for 14:59" counting down. Firestore: `families/{id}`, `members/{sarah uid}` role owner, `invites/{code}` kind device, expiring in 15 minutes.

### GM-FAM-002 · The phone joins with the code  `P0` `[B]`
**Steps:** on the emulator, fresh install, "Make this phone simpler" > "Set up with my family", type the code, Continue.
**Expect:** "Sarah would like to be your helper." with the explanation, "Yes, Sarah can help" and "No thanks". Firestore unchanged so far except a rate-limit doc (preview only). Tap "Yes, Sarah can help": the device doc gets `deviceUid`, model, Android version, app version; the invite is used up; the dashboard switches to "Connected!" within 15 s. Then the consent pages, lock level and code as in 02 and 03.

### GM-FAM-003 · Scanning the QR code  `P1` `[B]`
**Steps:** the Android emulator's back camera can show a picture: save the dashboard's QR code as a PNG and set it in Extended controls > Camera > Virtual scene images (start the emulator with `-camera-back virtualscene`). Then "Scan the code instead" on the phone and point the virtual camera at the wall image.
**Expect:** the code fills in and the flow continues to "Sarah would like to be your helper." If the virtual scene can't show it, mark BLOCKED and note GM-INST-008 covers the parsing on the real phone.

### GM-FAM-004 · Wrong, expired and reused codes  `P0` `[B]`
**Expect:**
- a wrong code: "That code didn't work. Double check it with your family member."
- five wrong tries against one real code lock it (later right code fails); check `rateLimits` docs
- an expired code (move the emulator host clock is hard; instead edit the invite's `expiresAt` in the Emulator UI to the past): "That code has expired. Ask your family member for a new one."
- a code already used by another phone: refused
- the same phone retrying a claim it already made: succeeds (by design, `firebase/README.md`)

### GM-FAM-005 · "No thanks"  `P1` `[B]`
**Expect:** back to Welcome, code cleared, nothing linked in Firestore.

### GM-FAM-006 · Offline during pairing  `P1` `[B]`
**Steps:** airplane mode on the emulator, enter a valid code.
**Expect:** "The phone isn't connected to the internet. Connect to Wi-Fi and try again." No spinner forever (time it).

## Live changes from the dashboard

### GM-FAM-010 · Change shows on the phone within 10 seconds  `P0` `[B]`
Spec bar 2. **Steps:** for each, change it on the dashboard, Save, and time until the phone shows it (stopwatch from clicking Save; screen recording on the emulator):
- Phone tab: text size up three steps; theme dark; high contrast
- Home screen tab: add a "Call Sarah" tile, reorder tiles, change to 3 columns, hide the photo card
- People tab: add David with a number and photo; make David the main helper
- Phone tab: allow the Notes stand-in (it appears in the dashboard's app picker only after the phone reported its apps; check that it did)
**Expect:** every change appears on the phone within 10 s with the phone on and online. Record each time. The phone's "Recent changes by your family" lists each one attributed ("Sarah: …"). Firestore `changes/` has matching entries.

### GM-FAM-011 · Changes while the phone is offline  `P1` `[B]`
**Steps:** airplane mode on the emulator, make three changes, wait, turn the network back on.
**Expect:** the phone catches up to the latest config within 10 s of reconnecting. Nothing is lost or applied out of order.

### GM-FAM-012 · The parent's change and the helper's change at once  `P1` `[B]`
**Steps:** within a few seconds: parent turns off "Long calls" on the phone; Sarah changes text size on the dashboard.
**Expect:** both survive. If one overwrites the other (the phone writes the whole config), that's a P1 bug: record which one was lost.

### GM-FAM-013 · The parent turning something off tells the family  `P0` `[B]`
**Steps:** on the phone, switch off pause cards, then the remote-control alarm.
**Expect:** a `PROTECTION_CHANGED_BY_PARENT` event per feature (created by the server), the dashboard shows it, the Functions log shows a notice to Sarah with the wording from `copy.ts`. Turning it back on: record whether anyone is told.

### GM-FAM-014 · Pausing tells the family  `P1` `[B]`
Suspect S-04. Pause everything from the app and from the notification. Is anything sent? Does the dashboard show the phone as paused, now or ever?

## Helpers

### GM-FAM-020 · A second helper needs the parent's yes  `P0` `[B]`
**Steps:** Sarah: Family settings > "Invite a helper", copy the link. In a second browser profile, sign in as `david@example.com` and open the link, accept.
**Expect:** David is `pending` (not in `memberUids`), can't see the device or alerts on the dashboard, gets no pushes. The phone's home screen shows a card "David would like to be one of your helpers." / "Only say yes if you know David and asked them to help." with "Yes, David can help" and a way to say no. Yes: David becomes active, sees the device. No (repeat with a third helper): they're removed. Sarah is told either way (check the log).

### GM-FAM-021 · A pending helper can't sneak in  `P0` `[B]`
**Steps:** as David while pending, from the browser console use the Firebase JS SDK with David's sign-in to read `families/{id}/devices`, write `config/current`, read events.
**Expect:** every one denied by the rules.

### GM-FAM-022 · The parent removes a helper  `P0` `[B]`
**Steps:** phone: "What my family can see" > Your helpers > Remove next to David > "Remove David?" > Remove (code at Guided+).
**Expect:** David loses access at once (dashboard shows he's out; reads denied). "Keep" changes nothing. Sarah is told.

### GM-FAM-023 · Up to 4 helpers and 2 phones  `P2` `[B]`
**Expect:** record what happens with a fifth helper invite and a third phone (the spec's plan limits). The test build may not enforce plans yet; note it.

## Photos from family

### GM-FAM-030 · Sarah sends a photo  `P0` `[B]`
**Steps:** dashboard Photos tab, upload a 4000x3000 JPEG with caption "Emma's first day of school".
**Expect:** within 10 s the phone's photo card shows it with "Today from Sarah" and the caption. Storage emulator has it (resized to 1600 px by the server; check). A HEIC and a PNG too. Twenty-one photos: the phone keeps the newest 20 from family, plus its own local ones. Removing a photo on the dashboard removes it from the phone. (Watch for the photo URL pointing at `127.0.0.1`, which the Android emulator can't reach; it needs `10.0.2.2`. If photos don't load, check that first and report it.)

### GM-FAM-031 · Caption length and odd characters  `P2` `[B]`
**Expect:** a 200-character caption with emoji and accented letters shows cleanly; 201 is refused by the dashboard.

## Alerts reach the family with the right words

### GM-FAM-040 · Every event, end to end  `P0` `[B]`
**Steps:** on the phone, trigger each event: pause card after a call (and during one), continue, call family, remote-control install and open, control-capable install, long call, Help, missed reminder, permission missing (revoke Usage access and wait for the status report, or check what triggers `PERMISSION_MISSING`), protection turned off.
**Expect:** for each: the event doc appears in Firestore within seconds of the phone being online (`outbox` empties in the state JSON), `deliveredTo` is set by the trigger, the dashboard Alerts tab shows the helper wording from `copy.ts` (for example "Mom opened Chase 4 minutes after a call from an unknown number. A pause card was shown."), urgent ones are marked urgent, and the Functions log shows the push. The phone's transparency list shows the parent-side wording. Put both wordings side by side in the report for Nate.

### GM-FAM-041 · Events queued offline are sent later  `P0` `[B]`
**Steps:** airplane mode, trigger three events, wait 5 minutes, network on.
**Expect:** all three arrive, in order, once each. No duplicates after a second reconnect.

### GM-FAM-042 · Protection settings from the dashboard  `P0` `[B]`
**Steps:** dashboard Protection tab: change the window to 10 minutes, long-call minutes to 10, add the Hometown CU stand-in as a bank (it appears in "App on the phone" from the phone's reported apps), set a safe word.
**Expect:** the phone follows: Hometown CU now gets a pause card after an unknown call; a call passing 10 minutes records the long-call event; the safe-word card reveals the new word.

### GM-FAM-043 · Trusted remote-control tool  `P0` `[B]`
Suspect S-17. **Steps:** dashboard: trust AnyDesk.
**Expect (per `docs/SECURITY.md` §3):** held until the parent approves on the phone, and every helper told. Record what actually happens. If it applies straight away, AnyDesk now opens with no warning: P0 security bug.

### GM-FAM-044 · Other risky changes  `P0` `[B]`
Suspect S-17. Same check for: changing Sarah's phone number (the number "Call Sarah first" dials), changing the main helper, turning off pause cards / remote-control alarm / long calls from the dashboard, and any remote unlock the dashboard offers (`lock.remoteUnlockUntil`). For each: applied at once, or held for the parent? Who is told?

### GM-FAM-045 · A helper can't write junk  `P1` `[B]`
**Steps:** as Sarah, from the browser console, write a config with an unknown field, a 10 MB string, a negative window, an invalid enum.
**Expect:** the rules or the phone reject or ignore it; the phone doesn't crash (it ignores unreadable configs, `CloudSync.listen`). Record what's accepted.

### GM-FAM-046 · The phone can't send arbitrary events  `P0` `[B]`
**Steps:** with the phone's own anonymous sign-in (get its ID token from the Auth emulator, or sign up a fresh anonymous user and claim a code), try to create events with an unknown type, extra fields (a phone number, a message), a huge `data` map; try to write another family's device; try to read events.
**Expect:** all denied (`validDeviceEvent` in `firestore.rules`). This is what keeps "events, never content" true even if the phone app were tampered with.

## Check-in

### GM-FAM-050 · Heartbeats  `P1` `[B]`
**Expect:** unlocking the phone or opening Guardian's home sets `status.lastHeartbeatAt` (at most every 15 minutes; unlock twice within a minute and confirm only one write). Nothing else about what the parent did is written.

### GM-FAM-051 · Missed check-in  `P1` `[B]`
The scheduled sweep doesn't run in the emulator. Run it by hand from `firebase/functions` after `npm run build`:
```powershell
$env:FIRESTORE_EMULATOR_HOST = "127.0.0.1:8080"; $env:GCLOUD_PROJECT = "demo-guardian"
node --input-type=module -e "const { db } = await import('./lib/lib/firebase.js'); const { runCheckInSweep } = await import('./lib/triggers/checkInSweep.js'); console.log(await runCheckInSweep(db(), new Date()))"
```
Set the device's check-in deadline (dashboard) to a time earlier today in its time zone, make sure there's no heartbeat today (edit `status.lastHeartbeatAt` in the Emulator UI to yesterday), run the sweep.
**Expect:** a `CHECK_IN_MISSED` event and helper wording "Mom's phone hasn't been used yet today." Running it again creates no duplicate. A heartbeat today: no event. Silent for 24 h: the "silent" variant.

## Leaving

### GM-FAM-060 · "Stop using Guardian Mode" leaves the family  `P0` `[B]`
**Expect:** the device and its subcollections are deleted on the server (`deleteDevice`), the dashboard shows the phone gone, helpers are told "this phone has left the family" (record the wording), the phone is back to setup with no pairing in its state, and its anonymous user is signed out.

### GM-FAM-061 · A helper deletes the device or family  `P1` `[B]`
**Expect:** record what the phone does when its device is deleted from the dashboard while it's on: it should stop syncing and not crash; say what the parent sees.
