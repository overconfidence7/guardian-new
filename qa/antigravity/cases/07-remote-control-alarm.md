# 07 · Remote-control app alarm

`docs/SPEC.md` F5, `core/TripwireEngine.kt` (`onPackageInstalled`, the remote-access branch of `onForeground`), `protection/AppWatchers.kt` (`InstallWatcher`).

**What should happen:** installing or opening a known remote-control app (AnyDesk, TeamViewer, RustDesk and the rest in `core/AppCatalog.kt`) shows a full-screen warning at once, whether or not there was a call. Installing any other new app that declares an accessibility service gets a milder warning. Apps already on the phone when Guardian was set up never alarm on install. Updates don't alarm. Opening the same remote-control app again warns again after 10 minutes.

**The warning** (`RemoteAccessCard`): headline "AnyDesk can let someone control your phone." (milder: "Screen Helper asks to control your screen."), "Real companies like Microsoft, Apple, Amazon or your bank never ask you to install an app like this over the phone.", callout "If someone on the phone asked you to install it, it's OK to hang up.", buttons "Call Sarah" (with a family member), "Remove AnyDesk", "I trust this app".

**Setup:** fast setup on `gm-api36` and `gm-api29-budget`. Stand-in APKs for AnyDesk, TeamViewer, RustDesk and Screen Helper. Clear logcat before each case.

### GM-RACC-001 · Installing AnyDesk  `P0` `[AVD]`
**Steps:** with Guardian set up and its notification showing, `adb install app-anydesk-debug.apk`. Start a screen recording first.
**Expect:** the warning within a few seconds of the install finishing (record the delay; target under 5 s with the screen on). Event `REMOTE_ACCESS_INSTALLED`, severity `URGENT`, `data.app: "AnyDesk"`, `afterUnknownCall: "false"`.

### GM-RACC-002 · Installing during an unknown call  `P0`
**Steps:** accept an unknown call, install TeamViewer during it.
**Expect:** warning; event `afterUnknownCall: "true"`. Then open Chase during the same call: the pause card (and later the long-call notice, if it gets there, is URGENT because a risky app was opened; see GM-CALL-003).

### GM-RACC-003 · Installing with the screen off  `P1`
**Steps:** screen off (`adb shell input keyevent 26`), install RustDesk, wait 30 s, turn the screen on and unlock.
**Expect:** the warning shows on unlock at the latest. Record exactly when.

### GM-RACC-004 · Install while Guardian's service isn't running  `P1`
**Steps:** `adb shell am force-stop app.guardianmode.debug`, install AnyDesk (uninstall first if needed), then press Home (Guardian starts again).
**Expect:** the warning appears once Guardian is running again. Record the delay and what triggered it.

### GM-RACC-005 · Opening a remote-control app  `P0`
**Steps:** AnyDesk installed and its install warning dismissed with "I trust this app". Open AnyDesk.
**Expect:** a warning on opening (`REMOTE_ACCESS_OPENED`, `NOTICE` when no call, `URGENT` within the 30-minute window after an unknown call). Open it again within 10 minutes: no warning. Move the clock 11 minutes: warning again.

### GM-RACC-006 · "Remove AnyDesk"  `P0`
**Expect:** Android's uninstall confirmation for AnyDesk opens (record the package that shows it). Confirming removes it. At Locked level, check the uninstall dialog for AnyDesk isn't itself covered by a code screen (the parent must be able to remove it without the code); if it is covered, that's a P0.

### GM-RACC-007 · "I trust this app"  `P1`
**Expect:** the warning closes. Note: on the phone this only dismisses; it doesn't add AnyDesk to the trusted list (helpers do that, Track B GM-FAM-043). Confirm by opening AnyDesk 11 minutes later: it warns again. Say in the report whether the button's wording promises more than it does.

### GM-RACC-008 · "Call Sarah"  `P1`
**Setup:** family state. **Expect:** dials Sarah, closes the warning.

### GM-RACC-009 · Apps that were already there  `P0`
**Steps:** fresh install of Guardian. Install AnyDesk first, then do setup.
**Expect:** no install warning when setup finishes or later. Opening AnyDesk still warns (it's a known remote-control app).

### GM-RACC-010 · Updates don't alarm  `P1`
**Steps:** AnyDesk installed and dismissed. `adb install -r app-anydesk-debug.apk` (same version reinstall), then bump the stub's `versionCode` and install that.
**Expect:** no new install warning.

### GM-RACC-011 · Reinstalling after removal alarms again  `P1`
**Expect:** uninstall AnyDesk, install it again: a new install warning.

### GM-RACC-012 · An app that can control the screen  `P0`
**Steps:** install Screen Helper (declares an accessibility service, isn't on any list).
**Expect:** the milder warning, "Screen Helper asks to control your screen."; event `CONTROL_APP_INSTALLED`, `NOTICE` (or `URGENT` within the window after an unknown call). Opening it later doesn't warn (only known remote-control apps warn on open). Then go to Settings > Accessibility and try to turn on Screen Helper's service during an unknown call on `gm-api36`: record what Android 16 does (it should block it during a call with a non-contact).

### GM-RACC-013 · An ordinary new app doesn't alarm  `P1`
**Expect:** installing the Notes stand-in shows nothing.

### GM-RACC-014 · Alarm switched off  `P1`
**Steps:** turn off "Remote-control app alarm" on the transparency screen, install TeamViewer, open AnyDesk.
**Expect:** no warning, no event. Turn it back on and open AnyDesk: warning. (Installs that happened while it was off: record whether they alarm later. They shouldn't.)

### GM-RACC-015 · During "Pause everything"  `P1`
**Expect:** nothing while paused. Record whether an install that happened during the pause is flagged after it ends (the spec doesn't say; report what happens and whether it should).

### GM-RACC-016 · Real AnyDesk from the Play Store  `P2` `[AVD]`
**Steps:** only on `gm-api36-play`, signed in with a throwaway Google account Nate approves (or skip). Install AnyDesk from Play. Never accept a connection. Uninstall afterwards.
**Expect:** the same warning as the stand-in. Note the real app's label and whether it declares an accessibility service.

### GM-RACC-017 · Every package in the built-in list is visible  `P1`
**Steps:** build extra stand-in flavors for a few more packages from `core/AppCatalog.kt` (add them to `stub-apps/app/build.gradle.kts` locally; don't commit unless Nate asks), for example `com.splashtop.sos`, `com.zoho.assist.agent`, `com.sand.airdroid`, `com.google.chromeremotedesktop`.
**Expect:** each warns on install and on open. Also compare `AppCatalog.BUILT_IN` remote-access entries with the `<queries>` `<package>` list in the manifest: every one must be in both (Android hides packages not listed). Report any mismatch.
