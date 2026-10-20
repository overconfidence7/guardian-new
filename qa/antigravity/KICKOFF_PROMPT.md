# Kickoff prompt

Paste everything inside the block into a new Antigravity agent conversation, with the guardian-new repo open from `C:\Users\nkurt\AndroidStudioProjects\guardian-new`.

```text
I need a full test pass on Guardian Mode, the Android app in this repo. It's a simple home screen for an older parent's phone that also slows down scams: pause cards when a bank or payment app opens during or right after a call from an unknown number, a warning when a remote-control app like AnyDesk shows up, quiet unknown callers, a safe word reminder, lock levels with a code, and a screen that shows the parent exactly what their family can see.

Everything you need is in qa/antigravity/. Start with README.md, then read RUNBOOK.md all the way through before you run anything. RUNBOOK.md has the rules, how to set up the emulators and builds, an adb cheat sheet, and how to write up results. Also read docs/SPEC.md, docs/POLICY.md and docs/SECURITY.md, since those decide what "correct" means when a case and the app disagree.

The short version of the plan:
1. Make a local branch called qa/antigravity-run-<today's date> from main. Don't push anything or touch any other branch without asking me.
2. Run the automated baseline in cases/00 so we know the build is healthy.
3. Set up the four emulators from RUNBOOK section 5 and build the stand-in apps in qa/antigravity/stub-apps (fake Chase, Venmo, AnyDesk and so on, so nothing real gets installed).
4. Work through every case in cases/01 to cases/19, in the order in RUNBOOK section 10. Track A uses the test APK from the "testing" release. Track B runs the Firebase emulators and the web dashboard locally so you can test family linking end to end.
5. Confirm or rule out every item in SUSPECTS.md. Those are problems I think are there from reading the code, and I want proof either way.
6. Spend at least an hour off script trying to break it, especially playing a scammer on the phone who is coaching the parent through what to tap. Add anything you find as new cases.
7. Write everything up in qa/antigravity/results/RUN-<date>/ using the templates. Keep REPORT.md current as you go so a stopped run still leaves something useful.

A few ground rules. Don't change app code. If something blocks a whole track, the smallest local patch to keep going is fine, but don't commit it and write it up as a bug. The real test phone is mine, so ask me before you install anything on it, wipe it, uninstall Guardian, or tap "Stop using Guardian Mode" on it. Don't call or text anyone from the real phone except numbers I give you. No real remote-control apps connected to anyone, and no signing in to real accounts.

Please batch your questions for me. When you hit steps tagged [HUMAN] (me calling the test phone from my own number, plugging the phone in, that kind of thing), collect them and ask me in one go with exact instructions. If you find a P0 (a scam gets through, the parent gets stuck, or something leaks off the phone), write it up and tell me in one line right away, then keep going.

When you're done, give me the summary from the top of REPORT.md: counts by result, the P0s in one line each, what you couldn't test and why, and the ten things you'd fix first. Let me know if anything in the runbook doesn't work on my machine and I'll sort it out.
```
