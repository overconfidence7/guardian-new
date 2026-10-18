# Guardian Mode: Firebase backend

Firestore and Storage rules, indexes, and the Cloud Functions behind the phone app and the
helper dashboard. The contract is `docs/API.md`; the privacy and security rules it follows are
`docs/POLICY.md` and `docs/SECURITY.md`.

```
firebase.json, .firebaserc        emulator ports, deploy targets (default project: demo-guardian)
firestore.rules, storage.rules    client access (functions use the Admin SDK and bypass them)
firestore.indexes.json            collection-group index for the check-in sweep, TTL policies
functions/src/index.ts            exports every function
functions/src/callables/          one file per callable in API.md, plus approveHelper
functions/src/triggers/           onEventCreated, onConfigWritten, onPhotoCreated, checkInSweep
functions/src/lib/                model mirror of Model.kt, default config, config diff, check-in
                                  logic, invite codes, rate limiting, push (FCM behind an interface)
functions/src/copy.ts             helper-facing notification wording for every EventType
functions/test/unit/              pure logic, no emulator (Vitest)
functions/test/emulator/          rules tests and end-to-end callable/trigger tests
```

## Requirements

- Node 22 and npm
- Java 21 (for the Firestore and Storage emulators)
- `firebase-tools` comes in as a dev dependency, so use `npx firebase …`. You don't need a global install.

## Run locally

```sh
cd firebase/functions
npm ci
npm run build          # TypeScript → lib/
npm run lint           # ESLint + typescript-eslint (type-checked)
npm run test:unit      # pure logic, no emulator needed
npm run test:emulator  # builds, starts auth/firestore/storage/functions emulators, runs rules + integration tests
npm run emulators      # build and start the full suite with the UI on http://127.0.0.1:4000
```

The project id `demo-guardian` needs no real Firebase project and no credentials. In the
emulator, push notifications go to a fake messenger that logs instead of calling FCM. Every
token counts as delivered except tokens starting with `dead-`, which act like tokens FCM has
unregistered (so you can test token cleanup).

The first emulator run downloads the Firestore and Storage emulator jars into
`~/.cache/firebase/emulators`. CI caches nothing extra; the download takes a few seconds.

The scheduled `checkInSweep` is not run by the emulator, because it needs the Pub/Sub emulator. Its logic is
`runCheckInSweep(firestore, now)`, which the integration tests call directly.

## Deploy

One-time setup:

1. Create the Firebase project on the **Blaze** plan (scheduled functions need it). Set a
   budget alert.
2. Enable Authentication (Google, Email link, Anonymous), Firestore (native mode), Storage and
   Cloud Messaging.
3. Add the project: `npx firebase use --add <project-id>` (it is stored in `.firebaserc` under an alias).
4. Optional: create `functions/.env.<project-id>` with
   `DASHBOARD_URL=https://<your dashboard>`. When set, web push notifications open the dashboard.

Deploy everything:

```sh
cd firebase/functions
npx firebase deploy --config ../firebase.json --project <project-id> --only functions,firestore,storage
```

`predeploy` runs lint and build. After the first deploy, the TTL policies in
`firestore.indexes.json` (`events.expireAt` 90 days, `invites.expiresAt`, `rateLimits.expireAt`)
can take up to a day to become active.

### Secrets

The repository holds no secrets. For deploys from CI:

| Secret | Used for |
|---|---|
| `FIREBASE_SERVICE_ACCOUNT` | JSON key of a deploy service account (roles: Firebase Admin, Cloud Functions Admin, Service Account User, Cloud Scheduler Admin). Write it to a file and set `GOOGLE_APPLICATION_CREDENTIALS` before `firebase deploy`. |

The functions themselves use the default runtime service account. FCM needs no key.

## Behaviour worth knowing

- **Pairing.** Device codes last 15 minutes and work once. Five failed lookups against one
  code lock it. Failed lookups are also limited to 10 per hour per user and 30 per hour per
  IP (stored hashed in `rateLimits/`). A retried claim by the same phone succeeds.
- **Helpers need the parent.** `acceptHelperInvite` creates a `pending` member outside
  `memberUids`. Only the family's phone can call `approveHelper` to activate or decline them.
  Pending helpers can read only their own member document and receive no pushes.
- **Idempotent triggers.** `onEventCreated` re-reads the event and skips it once
  `deliveredTo` is set. FCM messages carry the event id as a tag, so a retry collapses on
  the helper's device. Change entries and `PROTECTION_CHANGED_BY_PARENT` events use ids
  derived from the trigger event. Check-in events use ids derived from the local date or
  heartbeat, and the sweep re-checks inside a transaction.
