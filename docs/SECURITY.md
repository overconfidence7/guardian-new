# Security plan

Guardian Mode sits between a scammer and an older person's money, so it is itself a target. An attacker who takes over a helper account, or the link between the dashboard and the phone, could silence protection, change who "Call Sarah first" dials, or (if remote control exists) operate the phone. This plan assumes that attempt will happen.

## 1. Threat model

| Attacker | Goal | Main defenses |
|---|---|---|
| Phone scammer talking to the parent | Get the parent to disable Guardian, add the scammer as a "helper", install a remote-control app | Every sensitive change needs the parent *and* is announced to every helper; adding a helper needs an in-person code plus approval on the phone; remote-control alarm |
| Account thief (phished helper password, SIM swap) | Use a helper account to change the phone's settings or watch alerts | Passkey / 2FA required for helpers; new-device sign-in alerts to the other helpers; sensitive changes need on-phone approval; short sessions for remote help |
| Someone who steals or borrows the parent's phone | Read family data or turn Guardian off quietly | Phone holds no helper credentials; turning features off is announced to helpers; "leave family" is announced |
| Network attacker | Read or alter traffic | TLS everywhere (Firebase), certificate handling by the platform, WebRTC DTLS-SRTP for live sessions, no plaintext endpoints |
| Malicious app on the phone | Fake Guardian's cards, read its data, spoof its broadcasts | Non-exported components except the screening service (system-bound permission) and the PHONE_STATE receiver (permission-protected); no backups; no exported content providers |
| Insider / compromised backend | Read data at rest | Data minimization (events, never content); Firestore rules are the only access path for clients; admin access via audited service accounts only; screenshots auto-delete after 30 days |
| A family member acting against the parent | Monitoring or control the parent did not agree to | Parent-owned design: transparency screen, per-feature off switches, remove-helper on the phone, every remote session approved on the phone each time |

## 2. Identity and pairing

- **Helpers** sign in with Google or a passkey; email-link accounts must add a second factor (Firebase Auth / Identity Platform MFA) before they can change protection settings or start a live session.
- **The phone** has its own anonymous Firebase identity, created on the phone and bound to the family only through the in-person setup code. It holds no helper credentials.
- **Firebase App Check** on every client: Play Integrity on the phone, reCAPTCHA Enterprise on the web. Functions and Firestore reject requests without a valid token, so a script with a stolen ID token alone cannot talk to the backend.
- **Setup codes**: 6 characters from a 31-letter alphabet, single use, 15-minute expiry, rate-limited per IP and per family (5 wrong tries locks the code), generated with a CSPRNG.
- **Adding a helper** needs two things: an invite from an existing helper *and* the parent tapping "Allow David to help?" on the phone. A stolen helper account cannot quietly add an attacker.

## 3. Sensitive changes need the parent

These changes are held as "pending" until the parent approves them on the phone, and every helper is notified either way:

- Adding or removing a helper (a helper can always remove themselves).
- Changing a family member's phone number or the primary helper (the number every "Call Sarah first" button dials).
- Marking a remote-control app as trusted.
- Turning off pause tripwires, the remote-access alarm, or long-call alerts from the dashboard.
- Starting a live help session (see §5).

Other edits (tiles, photos, font size, reminders) apply immediately and appear in "Recent changes" on the phone.

## 4. Data protection

- **Minimization** is the strongest control: no messages, no call content, no numbers, no location, no usage history ever leave the phone (see `POLICY.md`).
- **Firestore and Storage rules** enforce family membership on every read and write; the phone can only append validated events and update its own status fields; invites are server-only. Rules have a unit test for every allow and deny case, run in CI on every push.
- **Retention**: events 90 days (Firestore TTL), help screenshots 30 days, deleted immediately with the device or family.
- **On the phone**: no Android backup or device transfer of Guardian data; local state is in app-private storage; FLAG_SECURE on the transparency and safe-word screens so the safe word can't be captured by a screen-recording app.
- **Secrets**: none in the repo. Signing keys and service-account keys live in GitHub encrypted secrets; Play App Signing holds the app key.

## 5. Remote help (live screen view and, later, control)

### What Android allows

- **Seeing** the parent's screen: `MediaProjection`. No special Play permission; Android shows its own consent dialog every time and a status-bar indicator while sharing.
- **Pointing**: Guardian can draw on the parent's screen (a pulsing circle where the helper taps, "tap here") with its existing "display over other apps" permission. No accessibility needed.
- **Controlling** (tapping and typing on the parent's behalf): on a normal, non-rooted phone the only route for a third-party app is an `AccessibilityService` that injects gestures. That is how TeamViewer QuickSupport and AnyDesk work. It means:
  - a Play Accessibility declaration and review (Guardian is not an "accessibility tool", so it is the stricter path), with prominent disclosure;
  - Guardian becomes exactly the kind of app its own remote-access alarm warns about, which a scammer could try to talk the parent into enabling;
  - Android 16 blocks turning on accessibility during a call with a non-contact, which helps.

### Recommendation

1. **Build "Live help" first (no accessibility):** helper starts a session from the dashboard or their phone → the parent's phone shows "Sarah wants to see your screen. Allow?" with a 2-word match code shown on both screens → the parent taps Allow → Android's own consent → live, end-to-end encrypted video (WebRTC) plus a voice call and on-screen pointing. This covers most real help ("tap the blue button at the bottom").
2. **Offer full control later as a separate, opt-in feature**, after a Play policy pre-review, with every safeguard below. It stays off unless the parent turns it on in person, and can be switched off from the transparency screen.

### Safeguards for any live session

- Requested by a helper with 2FA, approved on the phone by the parent **every time**, with matching 2-word codes on both screens to defeat a man in the middle.
- Peer-to-peer WebRTC with DTLS-SRTP; signaling through the family's Firestore space with a one-time session id; TURN relay credentials short-lived and scoped to the session.
- A large banner "Sarah is viewing your screen" with a one-tap Stop, always on top; sessions end after 15 minutes, on screen lock, or when an unknown-number call starts.
- When a bank, payment, crypto or gift-card app comes to the front, the shared picture is blanked and control (if enabled) is suspended.
- Control can never open Guardian's own settings, the remote-access trust list, Android's accessibility or security settings, or uninstall Guardian.
- Every session is listed on the phone's transparency screen and announced to all helpers.

## 6. Monitoring and response

- Alerts to all helpers on: new helper device sign-in, a protection feature turned off, a helper added or removed, a live session started.
- Cloud Audit Logs on admin actions; Crashlytics for crashes; a weekly dependency and secret scan in CI.
- A `SECURITY.md` disclosure contact on the website, and a pre-launch external penetration test of the backend rules, functions and pairing flow.
