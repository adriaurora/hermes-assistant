# Hermes v0.5.0 — FCM event notifications (Android)

Android-side contract for durable Hermes device events. The event inbox and
event state belong to Hermes; Android is a delivery client, not an event
database or poller.

## Contents

- [Architecture](#architecture)
- [Push transport and privacy](#push-transport-and-privacy)
- [Build: optional FCM](#build-optional-fcm)
- [Registration and authenticated fetch](#registration-and-authenticated-fetch)
- [Notification, tap, and acknowledgement](#notification-tap-and-acknowledgement)
- [Lifecycle management](#lifecycle-management)
- [Offline and failure handling](#offline-and-failure-handling)
- [Deployment checklist](#deployment-checklist)
- [Future event types](#future-event-types)

## Architecture

Hermes owns a durable device-event inbox. The server publishes a **data-only**
FCM message containing only `{"event_id": "..."}`; the phone receives that
message through Firebase Cloud Messaging, then the Android event path fetches
the event from Hermes and materializes one native notification.
`hermes/EventClient.kt` is the authenticated REST boundary for registration,
event fetch, pending events, and acknowledgement.

Android never polls on a timer. A push wake calls `receivers/PushIngress.kt`.
After a successful device registration, a one-shot, connectivity-constrained
pending-sync worker recovers the server's pending page. The FCM service enqueues
an event worker and supplies the event ID.

## Push transport and privacy

The chosen transport is **native Firebase Cloud Messaging (FCM)**:

1. Hermes publishes a data-only message containing only `{"event_id": "..."}`
   to the registered FCM token.
2. `FcmMessagingService` validates the event ID and schedules WorkManager.
3. The worker fetches the event over the authenticated Hermes API; notification
   title and body never travel through FCM.

FCM is a wake transport only. No `google-services.json`, credentials, or secrets
are committed; the app expects Firebase runtime dependencies to be provided
out of band. See [Build: optional FCM](#build-optional-fcm).

## Build: optional FCM

FCM is **optional**. The app builds cleanly without any Firebase config:

- When no `google-services.json` matching the app's `applicationId` is present,
  FCM is not available — the absence is explicit in the UI, not silent.
- When a matching config exists, the `com.google.gms.google-services` plugin
  is applied, Firebase Messaging is linked, and push is active.

Stale configs for old package names must not break credential-free builds.

## Registration and authenticated fetch

`data/DeviceRegistryStore.kt` stores four AndroidKeyStore-encrypted secrets
through `SecureStore`: `device_id`, the opaque FCM token (`push_endpoint`),
`hermes_origin`, and `push_api_key`. A `DeviceRegistration` is bound to the
Hermes origin that created it: the pinned `hermes_origin` + `push_api_key`
pair is used for `DELETE /api/devices/{device_id}`, and is not rewritten when
active settings change. The app never sends the new origin's credential to the
old origin, or vice versa.

When upgrading from the version that stored only `device_id` and the token,
`loadOrMigrate(currentSettings)` pins the active origin and bearer onto the
same device ID, idempotently (the first binding wins and the pin is never
rewritten), without creating another remote device or deleting anything. If
active settings are invalid (blank base URL or bearer), nothing is invented:
the record remains **legacy pending**, and neither revocation nor new
registration occurs until valid configuration is available. `push_api_key`
exists only while there is a real reason to retain it (a pending revoke or an
active device), is always encrypted, and is removed on revoke success/404, on
an unregistered-record purge, and never survives indefinitely after **Clear
 saved key**. If a bound record turns out to be unknown to the active origin
 (HTTP 404 on token update), the app registers a fresh device there and the
 stale remote record remains orphaned.

`hermes/EventClient.kt` uses the configured Hermes base URL and the existing
Bearer credential:

- `POST /api/devices/register` with `push_type: "fcm"` and `push_token`;
- save the returned `device_id` together with the endpoint;
- when the token changes, `POST /api/devices/{device_id}/token` with
  `push_type: "fcm"` and `push_token`;
- fetch with `GET /api/events/{event_id}`;
- acknowledge with `POST /api/events/{event_id}/ack`;
- revoke with `DELETE /api/devices/{device_id}`;
- recover pending work with `GET /api/events?status=pending&device_id=...`.

The same API key and base URL are reused for all event operations. Request
failures are sanitized to HTTP status/message snippets; credentials and event
content are not logged by this client.

## Notification, tap, and acknowledgement

`notifications/NotificationChannels.kt` creates the stable channel
**Hermes Reminders** (`IMPORTANCE_DEFAULT`). `events/EventLogic.kt` derives the
notification ID from `StableNotificationId.forEvent(event_id)`, so duplicate
wakes address the same Android notification. Notifications do not use a
full-screen intent or overlay.

The content `PendingIntent` is immutable and opens `MainActivity` with
`event_id`, `session_id` (when present), and a `from_notification` flag.
`receivers/PushIngress.kt` and `MainActivity` preserve that context so a tap
opens Hermes Assistant in the chat UI. **Taps do not auto-start voice recording**
or launch the assist gesture; notification extras are never interpreted as an
assistant authorization.

Delivery and acknowledgement are separate. A successful authenticated fetch
is enough to materialize the notification; `notifications/EventDelivery.kt`
then sends the best-effort ACK. A tap is not required for the ACK, and an ACK
failure does not undo a notification that was already delivered. Hermes ACKs
are idempotent, so retries and duplicate signals are safe.

## Lifecycle management

The FCM lifecycle (enable, disable, register, revoke) is serialized by a
non-reentrant in-process `Mutex` (`FcmLifecycle.withLock`). Every operation
re-reads `PushPrefs` state inside the lock before acting, so it self-heals
across process death.

**Enable**: set `enabled = true`, mark registration state as `REGISTERING`,
enqueue the token worker. The worker fetches the current Firebase token and
registers it with Hermes via HTTP.

**Disable**: immediately set `enabled = false`, mark `pendingRevoke = true`,
enqueue the revoke worker. The device registration is retained (not cleared)
until the revoke succeeds, so concurrent/future registration attempts are
blocked.

**Revoke**: a persistent WorkManager worker with a network constraint and
exponential back-off (30 s initial). Transient errors (network, 5xx, 429) are
retried without a local limit; 404 is idempotent success. 401/403 are classified
as **credential rejected**: the worker abandons the attempt, purges every local
copy (`device_id`, endpoint, origin, `push_api_key`, and, when a clear is
pending, the bearer via a defensive second deletion), and unblocks state. The
remote record is orphaned on the server in that case.

**Change of Hermes instance (A→B)**: changing origin marks revoke pending. The
DELETE is sent to origin A with A's pinned credential, never to B or with the
new bearer. After success, A's registry is purged and, if push is enabled, a
new device is registered against B. A bearer-only change on the same origin
does not recreate the device; it only refreshes the pinned credential, and is
skipped while a revoke is in flight.

**Clear saved key**: without a `DeviceRegistration`, the bearer principal is
cleared and any residual registry (`device_id`, endpoint, origin,
`push_api_key`) is purged. With a registration, local push is disabled
immediately, persistent DataStore flags `pendingCredentialClear` and
`pendingRevoke` are set, and only the pinned revoke credential is retained
temporarily. `DELETE /api/devices/{id}` runs against the pinned origin; on
success/404 everything is purged, including a defensive second bearer delete.
Transient failures retry while retaining only the credential needed for the
DELETE. While either pending flag is active, no new FCM registration occurs.
For legacy records, a record that can bind to active settings is revoked
against that origin; an unrevocable record (missing origin/credential) is
purged locally and left orphaned on the server.

**Pending retry**: after a successful registration, `FcmPendingWorker` fetches
`GET /api/events?status=pending` and delivers any events the phone missed. It
also retries transient sync failures; this is recovery work, not periodic polling.

## Offline and failure handling

Push fetch retry uses `RetryPolicy.MAX_RETRIES = 2` (WorkManager auto-retry).
`FcmRetryDecision` allows retry for `FETCH_FAILURE`, `DELIVERY_FAILURE`, and
`ACK_FAILURE` while below the limit.

The FCM token worker retries a failed registration up to two additional times
(`runAttemptCount < 2`), for at most three attempts per enqueued work item.

Expected cases:

- A push can arrive while the network is unavailable. The authenticated fetch
  fails without exposing content, and the constrained retry or pending sync
  can deliver it later.
- `NotificationDeduper` suppresses a duplicate event ID in the process and
  stable notification IDs prevent duplicate native notifications.
- An expired or unknown event (for example, a 404) is not materialized; the
  failed fetch is removed from the deduper so a later pending retry can try
  again. A successfully fetched event is ACKed idempotently.
- A revoked but not-yet-cleared device registration is cleaned up by the
  persistent revoke worker.

## Deployment checklist

- Provide Firebase project configuration (the Android
  `google-services.json` is intentionally not in this repository).
- Implement the Hermes backend registration contract above and publish
  data-only FCM messages containing an opaque `event_id`.
- The backend must expose the device-registration and event REST endpoints
  (`/api/devices/*`, `/api/events/*`).
- Configure the Hermes base URL reachable from the phone and reuse the existing
  Hermes Bearer API key.
- Grant Android 13+ notification permission (`POST_NOTIFICATIONS`).

A backend deployment must provide FCM credentials, token registration/revocation,
event fetch/ACK, and server-side retry/durability independently of this Android
project.

## Future event types

The envelope already carries `event_type`, optional `session_id`, title/body,
priority, and expiry metadata. Agent-run completion, approval events, and future
reminder kinds should reuse the same Hermes device event inbox, opaque-ID push
wake, authenticated fetch, dedupe, notification, and ACK path. Only event
mapping/presentation and any explicit action semantics should vary; do not
create a second Android polling or push system.
