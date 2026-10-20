# 18 · Wording

Two sets of rules.

**The spec's** (`docs/SPEC.md` bar 9): the parent is never told "scam", "fraud", "danger" or "alert". Copy says things like "Let's pause", "Did someone on the phone ask you to do this?", "It's OK to hang up." Helper wording (`firebase/functions/src/copy.ts`) follows the same rule because the parent can read it on the transparency screen. Never "monitor", "track" or "see what Mom is doing" (`docs/POLICY.md` §1 rule 5).

**Nate's:** every line should sound like a person wrote it, not an AI. Plain, warm, specific, short. No corporate filler ("seamlessly", "empower", "ensure", "leverage"), no stacked adjectives, no exclamation marks to sound friendly, no em-dashes, no "Please note that", no explaining what a button does when its label already says it.

### GM-COPY-001 · Banned words in parent-facing text  `P1`
**Steps:** `git grep -n -i -E "scam|fraud|danger|alert" -- android/app/src/main`. For each hit, decide whether a parent can see it (string literals in UI code, notification text, `res/values/strings.xml`) or not (comments, identifiers, log tags).
**Expect:** zero parent-visible hits. Suspect S-08 names two. List every one with file:line and a suggested replacement in Nate's voice.

### GM-COPY-002 · Helper-facing wording  `P2`
**Steps:** read every line in `firebase/functions/src/copy.ts` and the dashboard's event text (`web/src/lib/eventText.ts`), and every alert you saw in Track B.
**Expect:** calm and specific, the parent's name used naturally, no banned words, nothing the event doesn't carry (no numbers, no content). List anything that would scare or confuse a helper reading it on a lock screen at 2 a.m.

### GM-COPY-003 · Screen-by-screen read-through  `P1`
**Steps:** read every screenshot from the run aloud. For each screen, give it a score (fine / could be better / rewrite) and, for anything not fine, a rewrite.
**Expect:** a table in the report: screen, current text, problem, suggested text. Things to look for: jargon ("overlay", "role", "optimization", "default", "restricted"), instructions that don't match what Android actually shows (GM-CONS-003), the same idea worded two ways in two places (for example "pause card" vs "Hold on a minute"), messages that blame the parent, anything that sounds like a robot or a lawyer.

### GM-COPY-004 · The spec's example copy vs the app  `P2`
The spec's pause card says "Let's pause for a moment." / "Call Sarah first" / "I'm fine, continue"; the app says "Hold on a minute." The remote-control warning wording also differs. Note the differences and which reads better; don't treat the difference itself as a bug.

### GM-COPY-005 · Docs that are out of date  `P2`
**Steps:** follow `docs/TESTING.md` "Install on a real phone" exactly as written, on the Play image and the real phone.
**Expect:** note every step that doesn't match reality (app label, menu names, where "Allow restricted settings" lives on that Android version, the "Make this phone simpler" / "Set up on my own for now" labels).
