# Hermes v0.5.0 event notifications

This is the Android-side contract for durable Hermes device events. The event
inbox and event state belong to Hermes; Android is a delivery client, not an
event database or poller.

## Contents

- [Architecture](#architecture)
- [Push transport and privacy](#push-transport-and-privacy)
- [Registration and authenticated fetch](#registration-and-authenticated-fetch)
- [Notification, tap, and acknowledgement](#notification-tap-and-acknowledgement)
- [Offline and failure handling](#offline-and-failure-handling)
- [Deployment checklist](#deployment-checklist)
- [Future event types](#future-event-types)

## Architecture

Hermes owns a durable device-event inbox. The server publishes a wake signal
containing only `{"event_id": "..."}`; the phone receives that signal through
Firebase Cloud Messaging (FCM), then the Android event path fetches the event from Hermes and
materializes one native notification. `hermes/EventClient.kt` is the authenticated
REST boundary for registration, event fetch, pending events, and acknowledgement.

Android never polls on a timer. A push wake calls the ingress boundary in
`receivers/PushIngress.kt`; connectivity/app-start recovery calls
`EventDispatcher.onPendingSync()` for the server's pending page. The native FCM service enqueues a connectivity-constrained WorkManager job; the
job invokes this boundary and supplies the event ID.

## Push transport and privacy

The chosen transport is **native Firebase Cloud Messaging (FCM)**:

1. Hermes publishes a data-only message containing only `{"event_id": "..."}`
   to the registered FCM token.
2. `FcmMessagingService` validates the event ID and schedules WorkManager.
3. The worker fetches the event over the authenticated Hermes API; notification
   title and body never travel through FCM.

FCM is a wake transport only. No `google-services.json`, credentials, or secrets
are committed; the app expects the normal build-time Firebase configuration to be
supplied separately for a deployable release.

## Registration and authenticated fetch

`data/DeviceRegistryStore.kt` stores the `device_id` and `push_endpoint` as
Keystore-encrypted secrets through `SecureStore` aliases. The API key is stored
in the same Keystore-backed store; it is never put in DataStore, BuildConfig,
or HTTP logs.

`hermes/EventClient.kt` uses the configured Hermes base URL and the existing
Bearer credential:

- `POST /api/devices/register` with `push_type: "fcm"` and `push_token`;
- save the returned `device_id` together with the endpoint;
- when the token changes, `POST /api/devices/{device_id}/token` with `push_type: "fcm"` and `push_token`;
- fetch with `GET /api/events/{event_id}`;
- acknowledge with `POST /api/events/{event_id}/ack`;
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
`event_id`, `session_id` (when present), and notification-origin context.
`receivers/PushIngress.kt` and `MainActivity` preserve that context so a tap
opens Hermes Assistant in the relevant session.

Delivery and acknowledgement are separate. A successful authenticated fetch
is enough to materialize the notification; `notifications/EventDelivery.kt`
then sends the best-effort ACK. A tap is not required for the ACK, and an ACK
failure does not undo a notification that was already delivered. Hermes ACKs
are idempotent, so retries and duplicate signals are safe.

## Offline and failure handling

`RetryPolicy` permits at most `MAX_RETRIES = 2`; `RetryGate` applies that rule
to a failed push fetch when connectivity returns. This is a one-off,
connectivity-constrained retry, not periodic polling. `onPendingSync()` is the
recovery path at connectivity/app start and processes Hermes' durable pending
page.

Expected cases:

- A push can arrive while WireGuard is disconnected. The authenticated fetch
  fails without exposing content, and the constrained retry/pending sync can
  deliver it later.
- `NotificationDeduper` suppresses a duplicate event ID in the process and
  stable notification IDs prevent duplicate native notifications.
- An expired or unknown event (for example, a 404) is not materialized; the
  failed fetch is removed from the deduper so a later pending retry can try
  again. A successfully fetched event is ACKed idempotently.

## Deployment checklist

- Provide Firebase project configuration out of band (the Android
  `google-services.json` is intentionally not in this repository).
- Implement the Hermes backend registration contract above and publish
  data-only FCM messages containing an opaque `event_id`.
- Configure the Hermes base URL reachable from the phone and reuse the existing
  Hermes Bearer API key.
- Grant Android 13+ notification permission.

The `_remote_*` Python scripts found in the recovery source are operator-side
experiments, not Android build inputs and are not part of this repository's
executable backend. They were not deployed by this change. A backend deployment
must provide FCM credentials, token registration/revocation, event fetch/ACK, and
server-side retry/durability independently of this Android project.

## Future event types

The envelope already carries `event_type`, optional `session_id`, title/body,
priority, and expiry metadata. Agent-run completion, Bufanatic approval, Home
Assistant events, and future reminder kinds should reuse the same Hermes device
event inbox, opaque-ID push wake, authenticated fetch, dedupe, notification,
and ACK path. Only event mapping/presentation and any explicit action semantics
should vary; do not create a second Android polling or push system.
