# Guardian Mode test run, YYYY-MM-DD

## Summary

- **Build:** testing release commit `…` (build #…); Track B debug build from commit `…`
- **Devices:** gm-api29-budget, gm-api33, gm-api36, gm-api36-play, real phone (make, model, Android version)
- **Results:** N cases run. PASS n · FAIL n · BLOCKED n · SKIP n · N/A n
- **By priority:** P0 failed n of N · P1 failed n of N · P2 failed n of N

### P0 failures, one line each

1. BUG-… · …

### What I couldn't test, and why

- …

### The ten things I'd fix first

1. …

## Bugs

(One entry per bug, in the format from RUNBOOK §9, most severe first.)

## Suspects

| ID | Verdict | Evidence | Bug |
|---|---|---|---|
| S-01 | Confirmed / Ruled out / Partly | evidence/… | BUG-… |

## Scam scenarios

For each GM-SCAM case: the timeline, what Guardian did at each step, and the verdict (stopped it / slowed it / didn't help), with the reason.

### How a scammer could switch Guardian off (from GM-SCAM-005)

| Step | Simple | Guided | Locked | Code needed? | Family told? | Protection lost |
|---|---|---|---|---|---|---|

## Timings

| Measure | Target | gm-api29-budget | gm-api36 | real phone |
|---|---|---|---|---|
| Cold start | < 1.5 s | | | |
| Call family from picking up the phone | < 5 s | | | |
| Pause card after the app opens | < 2 s | | | |
| Locked-app cover | ~1 s | | | |
| Remote-control install warning | < 5 s | | | |
| Dashboard change reaches the phone | < 10 s | | | |
| Battery per day | < 2% | | | |

## Wording

| Screen | Current text | Problem | Suggested |
|---|---|---|---|

## New cases I added

GM-NEW-001 … with steps, expected, result.

## Notes for Nate

Product questions the run raised that only Nate can answer (for example: should calls the parent places arm pause cards?), each with a one-line recommendation.
