# 05 · Photos

Two ways in on the phone itself (`docs/SPEC.md` F15): Android's photo picker from "Family photos" > "Add photos from this phone", and Share > "Add to photo card" from any app. Pictures are resized to 1600 px on the longest side and kept on the phone only; nothing is uploaded. Up to 30 are kept. Family photos from the dashboard are Track B (GM-FAM-030).

Put test images on the emulator first: `adb push <file> /sdcard/Pictures/` then `adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures/<file>` (or open them once in the Photos/Files app). Make a set: a 4000x3000 JPEG, a portrait 3000x4000 JPEG, a PNG with transparency, a HEIC (from a real phone), a 50 MB panorama, a 1x1 GIF, a WebP, a corrupt file named `.jpg`.

### GM-PHOTO-001 · Empty state  `P2`
**Expect:** "Family photos" says "When your family sends photos, you'll find them here.", an "Add photos from this phone" button, and the hint about tapping Share in Google Photos, Amazon Photos or the gallery and picking "Add to photo card".

### GM-PHOTO-002 · Add from the picker  `P0`
**Steps:** "Add photos from this phone", pick three images.
**Expect:** no permission prompt (the system picker needs none). All three appear on "Family photos" newest first. The home screen photo card shows the newest. No `READ_MEDIA_IMAGES` permission requested at any point.

### GM-PHOTO-003 · Share one picture from another app  `P0` `[AVD]` `[PHONE]`
**Steps:** in Google Photos (or the Files app / gallery on the emulator), open a picture, Share, pick "Add to photo card" (record the exact label and icon in the share sheet).
**Expect:** "Adding your photos", then "Added to your photo card" and "The picture is on your home screen now.", with "Go to my home screen" and "Back to the other app". Both buttons do what they say. The home screen shows the picture. On the real phone, also try Amazon Photos if installed (ask Nate).

### GM-PHOTO-004 · Share several  `P1`
**Steps:** select 5 pictures, Share, "Add to photo card".
**Expect:** "The 5 pictures are on your home screen now." All five show.

### GM-PHOTO-005 · Pictures are resized and kept privately  `P1`
**Steps:** share the 4000x3000 JPEG. `adb shell run-as app.guardianmode.debug ls -l files/photos`, pull one file (copy to /data/local/tmp first, as in RUNBOOK §7) and check its size.
**Expect:** saved as JPEG, longest side 1600 px, a few hundred KB. Stored in app-private `files/photos/`, nowhere on shared storage. Nothing sent over the network (logcat and, in Track B, the Storage emulator show no upload).

### GM-PHOTO-006 · Odd files  `P1`
**Steps:** share the PNG, HEIC, WebP, GIF, the 50 MB panorama and the corrupt `.jpg`, one at a time.
**Expect:** each either works or shows "That didn't work" / "Those pictures couldn't be added. Try sharing them again." No crash, no hang over 10 s, no out-of-memory on `gm-api29-budget` with the panorama (watch logcat for `OutOfMemoryError`).

### GM-PHOTO-007 · The 30-photo limit  `P1`
**Steps:** add 35 pictures in batches.
**Expect:** the 30 newest are kept; the oldest go, and their files are deleted from `files/photos` (count them).

### GM-PHOTO-008 · Remove a photo  `P1`
**Expect:** each local photo has "Remove this photo". It disappears from the page and the home card, and its file is deleted. Removing the last one returns the card to its empty text.

### GM-PHOTO-009 · Photos survive restarts and app updates  `P0`
**Steps:** add photos, force-stop, reopen; reboot; install the same APK again with `adb install -r`.
**Expect:** all photos still there and showing.

### GM-PHOTO-010 · Photo source permission runs out  `P2`
**Steps:** share from Google Photos, then clear Google Photos' data or uninstall it.
**Expect:** the picture stays on Guardian's card (it was copied, not linked).

### GM-PHOTO-011 · Photo card wording  `P2`
**Expect:** a photo added today reads "Today"; with a sender (family photo in Track B), "Today from Sarah". Yesterday: "Yesterday". Older: the weekday name. Note: is a weekday name confusing for a photo from 3 weeks ago? Suggest better if so.

### GM-PHOTO-012 · Share target is visible at Locked  `P2`
**Steps:** at Locked, from the Camera tile take a picture, then share it from the camera's preview.
**Expect:** the share sheet works and "Add to photo card" works. Record whether opening the camera's gallery leads to an app that's covered (Google Photos, if not on the allowed list).
