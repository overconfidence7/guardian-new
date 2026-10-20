# Working in this repo

Guardian Mode: Android app (`android/`), pure-Kotlin rules (`core/`), Firebase backend (`firebase/`), family dashboard (`web/`). The spec is `docs/SPEC.md`; Play policy limits are in `docs/POLICY.md`; the threat model is `docs/SECURITY.md`.

## Tests grow with the app

A daily workflow runs every test in the cloud and a Claude scheduled task acts on the report (`qa/daily/README.md`). To keep it complete:

- Every feature or bug fix adds or updates a case in `qa/antigravity/cases/` and a test whose name carries that case id (`gm_pause_004_...` in Kotlin, `GM-PAUSE-004 ...` in JS).
- Use the lowest layer that proves the behavior: `core` rule test, then a JVM/Compose test in `android/app/src/test`, then an on-device test in `android/app/src/androidTest` (use `support/DeviceRule`).
- A test for a bug that isn't fixed yet asserts the right behavior, carries the known-bug marker, and gets an entry in `qa/daily/known-bugs.json`. Remove both in the same change as the fix.
- Parent-facing text never says "scam", "fraud", "danger", "alert" or "monitor" (`docs/SPEC.md` §2.9); a test checks this.
