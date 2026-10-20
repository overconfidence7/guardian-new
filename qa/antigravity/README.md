# Guardian Mode: Antigravity test suite

A hands-on test pass for the Guardian Mode Android app, written for an agent (Google Antigravity) driving Android emulators, a real test phone over USB, and the local Firebase emulators on Nate's Windows PC. It complements the automated tests in CI (`docs/TESTING.md`): CI proves the rules; this suite proves the app works for a real person on a real phone, and tries hard to break it.

Start with [RUNBOOK.md](RUNBOOK.md). The kickoff prompt to paste into Antigravity is in [KICKOFF_PROMPT.md](KICKOFF_PROMPT.md).

## What's here

| Path | What |
|---|---|
| `RUNBOOK.md` | Rules for the agent, machine setup, devices, adb cheat sheet, how to record results |
| `KICKOFF_PROMPT.md` | The prompt that starts a run |
| `SUSPECTS.md` | Problems spotted while reading the code. Each one must be confirmed or ruled out on a device |
| `cases/` | The test cases, one file per area, each case with an ID, priority, steps and what should happen |
| `stub-apps/` | Tiny stand-in apps that install under watched package names (Chase, Venmo, AnyDesk...) so tripwires can be tested without the real apps |
| `templates/` | The report and results sheet the run fills in |

## Tracks

| Track | Needs | Covers |
|---|---|---|
| **A. Solo build** | The `guardian-mode-test.apk` from the GitHub release tagged `testing`, emulators, the test phone | Everything that works without family linking: setup, home screen, lock levels, pause cards, remote-control alarm, call screening, safe word, transparency, photos, notifications, robustness, accessibility, privacy |
| **B. Full stack, local** | A debug build pointed at the local Firebase emulators, the web dashboard on localhost, an emulator | Family linking and everything a helper does: pairing, live config changes, photos from family, alerts with the exact helper wording, helper approval, removing helpers, reminders, Help screenshots, "Stop using" leaving the family |
| **C. Automated baseline** | The repo checkout | The repo's own unit, screenshot, instrumented, backend and dashboard tests run locally, so a failure in A or B can be told apart from an existing break |

Run order: C first (quick, tells you if the build is healthy), then A, then B.

## Case IDs and priorities

IDs look like `GM-LOCK-012`. The middle part names the file:

| Code | File |
|---|---|
| BASE | `cases/00-automated-baseline.md` |
| INST | `cases/01-install-and-first-run.md` |
| CONS | `cases/02-setup-and-consent.md` |
| LOCK | `cases/03-lock-levels-and-code.md` |
| HOME | `cases/04-home-screen.md` |
| PHOTO | `cases/05-photos.md` |
| PAUSE | `cases/06-pause-cards.md` |
| RACC | `cases/07-remote-control-alarm.md` |
| SCRN | `cases/08-call-screening.md` |
| CALL | `cases/09-long-calls-and-safe-word.md` |
| TRAN | `cases/10-transparency-and-pause.md` |
| SVC | `cases/11-service-and-notifications.md` |
| HELP | `cases/12-help-and-reminders.md` |
| FAM | `cases/13-family-linking-e2e.md` |
| PRIV | `cases/14-privacy-security-policy.md` |
| A11Y | `cases/15-accessibility.md` |
| ROB | `cases/16-robustness.md` |
| PERF | `cases/17-performance-and-battery.md` |
| COPY | `cases/18-wording.md` |
| SCAM | `cases/19-scam-scenarios.md` |

- **P0**: the app's promise. A failure here means a scam gets through, the parent is trapped, or something leaks. Stop and report it at once.
- **P1**: a feature doesn't work as the spec says.
- **P2**: polish, wording, edge cases.

Tags: `[AVD]` runs on an emulator, `[PHONE]` needs the real test phone, `[HUMAN]` needs Nate to do something (place a real call, look at the phone). `[B]` needs Track B.

## Ground truth

When a case and the code disagree, the spec wins: `docs/SPEC.md`, `docs/POLICY.md`, `docs/SECURITY.md`. When the spec is silent, report what you saw and say which behavior you'd expect.
