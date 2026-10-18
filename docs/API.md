# Backend contract

The single source of truth for how the phone, the dashboard and Firebase talk. Kotlin models: `core/src/main/kotlin/app/guardianmode/core/Model.kt`. Fixture: `shared/fixtures/device-config.example.json`. Local and CI testing use the Firebase Emulator Suite with project id `demo-guardian` (the `demo-` prefix needs no real project).

## Identities

- **Helper**: a Firebase Auth user (Google or email link). Member of one or more families.
- **Phone**: a Firebase *anonymous* Auth user created on first launch. Becomes the device's `deviceUid` when it claims a setup code.

## Firestore

```
families/{familyId}
  name: string
  plan: "free" | "family"
  ownerUid: string
  memberUids: string[]                 # denormalized for rules and queries
  createdAt: timestamp

families/{familyId}/members/{uid}
  role: "owner" | "helper"
  displayName: string
  email: string
  photoUrl: string | null
  phone: string                        # used to build FamilyContact entries
  pushTokens: string[]                 # web/Android FCM tokens for this helper
  notify: { urgent: "push", notice: "push" | "off" }
  status: "pending" | "active"         # helpers other than the creator wait for the parent's approval on the phone
  joinedAt: timestamp

families/{familyId}/devices/{deviceId}
  parentName: string
  deviceUid: string | null             # anonymous uid of the paired phone
  fcmToken: string | null
  model, androidVersion, appVersion: string
  status: { protected: bool, missing: string[], lastHeartbeatAt: timestamp, lastSeenAt: timestamp }
  installedApps: [{ packageName, label }]
  checkIn: { enabled: bool, deadline: "HH:mm", timezone: IANA }   # copied from config by onConfigWritten
  checkInNotifiedFor: "yyyy-MM-dd" | null
  silentNotifiedAt: timestamp | null
  consent: { version: int, acceptedAt: timestamp, features: map<string,bool> }
  createdAt: timestamp

families/{familyId}/devices/{deviceId}/config/current
  ...DeviceConfig JSON...
  updatedBy: { uid, name, kind: "helper" | "device" }
  updatedAt: timestamp

families/{familyId}/devices/{deviceId}/events/{eventId}
  type: EventType, severity: Severity, at: number (ms, phone clock),
  data: map<string,string> (≤ 10 keys, values ≤ 200 chars),
  createdAt: timestamp (server), source: "device" | "server",
  deliveredTo: string[] (set by functions)

families/{familyId}/devices/{deviceId}/photos/{photoId}
  storagePath, fromUid, fromName, caption, createdAt

families/{familyId}/devices/{deviceId}/changes/{changeId}
  byUid, byName, summary, at         # written by onConfigWritten, shown on the phone

invites/{code}                        # top-level so a code can be looked up without knowing the family
  familyId, deviceId | null, kind: "device" | "helper", createdBy, createdByName, expiresAt, usedBy | null
```

### Rules summary

- Helpers (uid in `families/{f}.memberUids`): read the family, members, devices, config, events, photos, changes; write config, photos; update their own member doc; update device `parentName`.
- Phone (`request.auth.uid == devices/{d}.deviceUid`): read its device, config, photos, changes, member names; update its device doc fields `fcmToken, model, androidVersion, appVersion, status, installedApps, consent`; write config (the parent owns the phone and may switch features off); create events with a valid `type`/`severity`, `source == "device"`, bounded `data`.
- Invites: no client access; only through functions.
- Everything else denied.

## Storage

- `families/{f}/devices/{d}/photos/{photoId}.jpg`: helpers write (≤ 10 MB, image/*), helpers and the phone read. The dashboard resizes to 1600 px before upload.
- `families/{f}/devices/{d}/help/{id}.jpg`: the phone writes (≤ 5 MB, image/jpeg), helpers read.

## Callable functions (region `us-central1`)

| Name | Caller | Input | Output |
|---|---|---|---|
| `createFamily` | helper | `{ familyName, parentName, helperPhone? }` | `{ familyId, deviceId }` (creates owner member, device with default config) |
| `addDevice` | helper | `{ familyId, parentName }` | `{ deviceId }` (max 2 devices on the family plan, 1 on free) |
| `createDeviceInvite` | helper | `{ familyId, deviceId }` | `{ code, expiresAt }` (6 chars from `ABCDEFGHJKMNPQRSTUVWXYZ23456789`, 15 min, single use; 5 failed lookups lock it) |
| `previewInvite` | phone | `{ code }` | `{ kind, familyName, parentName, helperName }` |
| `claimDeviceInvite` | phone | `{ code, model, androidVersion, appVersion }` | `{ familyId, deviceId }` (sets `deviceUid`; replaces a previous phone) |
| `createHelperInvite` | helper | `{ familyId }` | `{ code, expiresAt }` (max 4 helpers on the family plan, 1 on free) |
| `acceptHelperInvite` | helper | `{ code, displayName, phone? }` | `{ familyId, status: "pending" }` (member doc created with `status: "pending"`; not in `memberUids` until the phone approves) |
| `approveHelper` | the family's phone | `{ familyId, uid, approve: bool }` | `{}` (parent's on-phone decision; approve adds to `memberUids` and sets `status: "active"`, decline deletes the member) |
| `removeHelper` | owner helper, or the family's phone | `{ familyId, uid }` | `{}` (the owner can't be removed by the phone if they're the only helper) |
| `deleteDevice` | owner helper, or the phone itself | `{ familyId, deviceId }` | `{}` (deletes subcollections and storage) |
| `deleteFamily` | owner | `{ familyId }` | `{}` |

Errors use `HttpsError` codes: `unauthenticated`, `permission-denied`, `not-found`, `failed-precondition` (expired/used code, plan limit), `invalid-argument`, `resource-exhausted` (rate limited). `details.reason` narrows it: `bad-code`, `unknown-code`, `wrong-kind`, `used`, `expired`, `locked`, `plan-limit`, `last-helper`, `owner`, `already-active`, `rate-limited`.

Notes on the implemented contract:
- `expiresAt` is epoch milliseconds. Codes are accepted in any case, with spaces or dashes. Helper invites last 7 days.
- A device code locks after 5 failed lookups of that code; unknown codes count against the caller (10/hour per uid, 30/hour per hashed IP, in the server-only `rateLimits/` collection).
- `createFamily` and `addDevice` take an optional `timezone` (default `America/New_York`). Plan limits count active and pending helpers.
- `removeHelper` also withdraws a pending request; a helper may remove themselves; if the phone removes the owner, ownership passes to the next member.
- Approving a helper who gave a phone number adds them to each device config's `family` list.
- Extra fields: `families.deviceUids` (rules use it to let the phone read members), `members.approvedAt`, `invites.failedAttempts` / `usedAt`, `events.expireAt` (90-day TTL, set by `onEventCreated`).

## Triggers

- `onEventCreated` (events): looks up members, sends FCM to each member's `pushTokens` (URGENT and NOTICE per `notify`, never INFO), removes dead tokens, writes `deliveredTo`. Notification text comes from `firebase/functions/src/copy.ts` (helper-facing wording).
- `onConfigWritten` (config/current): if `updatedBy.kind == "helper"`, writes a `changes` entry with a human summary of what changed and sends an FCM data message `{ type: "config" }` to the phone. If `kind == "device"` and a protection feature went from on to off, creates a `PROTECTION_CHANGED_BY_PARENT` event (source `server`). Always copies `checkIn` to the device doc.
- `checkInSweep` (every 15 min): for each device with `checkIn.enabled`, if parent-local time is past the deadline, `lastHeartbeatAt` is before parent-local midnight, and `checkInNotifiedFor` isn't today: create `CHECK_IN_MISSED` (NOTICE). If `lastHeartbeatAt` is older than 24 h and `silentNotifiedAt` is older than that: create `CHECK_IN_MISSED` with `data.reason = "silent"` (URGENT).

- `onPhotoCreated` (photos): sends `{ type: "photo" }` to the phone.

## Event data keys

Values are strings. Never a phone number, message or screen content.

| Type | Keys |
|---|---|
| `LONG_UNKNOWN_CALL` | `minutes`, `ongoing` |
| `PAUSE_*`, `REMOTE_ACCESS_*`, `CONTROL_APP_INSTALLED` | as emitted by `TripwireEngine.kt` (app label and category) |
| `HELP_REQUESTED` | `note` |
| `REMINDER_MISSED` | `title`, `time` |
| `PROTECTION_CHANGED_BY_PARENT` | `feature` (a `Transparency.kt` key, `safeWord` or `all`), `enabled` |
| `PERMISSION_MISSING` | `permission` (`usageAccess`, `overlay`, `callScreening`, `phoneState`, `notifications`, `exactAlarms`, `fullScreenIntent`, `battery`, `homeApp`, `contacts`) |
| `CHECK_IN_MISSED` | `deadline`, `date`, or `reason: "silent"`; server-created only |

## FCM messages to the phone

Data-only, high priority: `{ type: "config" }` (refetch config) or `{ type: "photo" }` (new photo). The phone also listens to Firestore while its service runs, so FCM is only the wake-up path.
