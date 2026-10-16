# Guardian Mode

Your phone, with your family in your corner.

Guardian Mode turns a parent's Android phone into a calm, simple phone that family can set up from afar, and that slows a scam down at the moments scams actually happen: a pause card when a bank, payment, gift-card or crypto app opens during or right after a call from an unknown number, an alarm when a remote-control app appears, unknown-caller screening, long-call alerts, a family safe word, a passive daily "I'm OK", reminders, and a transparency screen showing the parent exactly what is shared. Events are shared, never content.

| Folder | What |
|---|---|
| `core/` | Pure Kotlin rules engine (call tracking, screening, tripwires, reminders, transparency) |
| `android/` | The Android app (Kotlin, Jetpack Compose) |
| `web/` | Helper dashboard (React, installable PWA, works on iPhone) |
| `firebase/` | Firestore/Storage rules and Cloud Functions |
| `shared/` | JSON fixtures shared by the Kotlin and TypeScript tests |
| `docs/` | [Spec](docs/SPEC.md), [policy analysis](docs/POLICY.md), [build plan](docs/BUILD_PLAN.md), [API contract](docs/API.md), [testing](docs/TESTING.md) |

## Quick start

```bash
cd core && ./gradlew test                     # rules engine
cd android && ./gradlew testDebugUnitTest     # app unit + screenshot tests
cd web && npm ci && npm test                  # dashboard
cd firebase/functions && npm ci && npm test   # backend
```

All of it runs on GitHub Actions on every push; see `docs/TESTING.md`.
