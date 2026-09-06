# Migration Strategy — legacy enrollment → Wire Protocol v1

**Status:** IMPLEMENTED IN PROGRESS (branch `feature/wire-protocol-v1`)  
**Date:** 2026-09-06

## 1. Estado local hoy (legacy)

The current legacy enrollment is:

```text
DeviceRegistration{deviceId, pushEndpoint (= FCM token), hermesOrigin, apiKey}
```

It is stored in SecureStore under `hermes_device_id`,
`hermes_push_endpoint`, `hermes_push_origin`, and `hermes_push_api_key`.
`PushPrefs` DataStore contains `enabled`, `distributor`, `registration_state`,
and `pending_revoke`. There is no `device_secret`; therefore this enrollment
cannot be reused for v1.

## 2. Migration (Phases F/G)

The migration flow is:

```text
legacy enrollment
  → capability probe says v1 available
  → best-effort legacy revoke (failure ignored)
  → fresh device.register(label + push{type:fcm, token})
  → new device_id + device_secret
  → persist secret FIRST, device_id LAST (device_id present means enrolled)
  → persist push_protocol=v1
  → enqueue pending sync
```

The client never derives or invents `device_secret`, and never attempts to
claim the legacy device ID. The protocol rejects that path
(`legacy_pending_enrollment` → `device_revoked` 409). Re-enrollment is always a
fresh registration. The legacy device remains an orphan in
`legacy_pending_enrollment`/`active` server-side. A best-effort legacy revoke is
attempted **before** fresh registration and its failure is ignored.

Orphan growth is accepted: only a persistence failure after registration can
create these extra devices. Server cleanup is possible but out of scope.

## 3. Secure secret persistence (Phases D/E)

Create SecureStore alias `hermes_device_secret`, using the existing
`KeystoreAeadCipher` (AES-256-GCM). Registration is not complete until both
`device_id` and `device_secret` are persisted. Write the secret first and the
device ID last: if persistence fails, `load()` returns null rather than a
partially enrolled state, and the next attempt repeats `device.register` (and
creates a new server-side device as described in section 2).

Never log `device_id` and `device_secret` together.

## 4. Token rotation (Phase H)

`Firebase onNewToken` enqueues `FcmTokenWorker` with `REPLACE` and
`CONNECTED`. Under the existing non-reentrant `FcmLifecycle` lock:

| Local enrollment | Operation |
|---|---|
| v1 | `device.token.update {device_id,device_secret,push_token}` |
| legacy (`push_protocol=legacy`) | Legacy `updateFcmToken` |
| none | Register; the token already persisted in SecureStore `pushEndpoint` when `onNewToken` arrived, while `currentToken()` is reread as authoritative |

The worker rereads state inside the lock, avoiding races between register,
`onNewToken`, disable, and revoke. On `device_auth_failed`, `device_not_found`,
or `device_revoked` from token update, clear local enrollment and fresh-register
under the same lock, with at most one mutating operation.

## 5. Revoke (Phase I)

v1 revoke sends `device.revoke` with both credentials.

| Response | Classification and local action |
|---|---|
| `ok:true` | Confirmed; delete local enrollment |
| `device_not_found` | Device already absent; treat as confirmed and delete local enrollment |
| `device_revoked` | Already revoked; treat as confirmed and delete local enrollment |
| `device_auth_failed` | Do not delete credentials; set ERROR and stop after bounded retries (manual server cleanup gap) |
| network/5xx | Retry through persistent WorkManager |

Credentials are never deleted before a valid response that makes retry
impossible. The revoke worker therefore retains them through transient failure.

## 6. Rollback

Persist `push_transport` (`auto|legacy|v1`) in `PushPrefs`; default `auto` uses
the capability probe. On rollback to legacy 0.20.5, a 404 probe selects the
legacy client. Legacy local enrollment is not destroyed by v1 migration except
through explicit revoke: when migration starts, the legacy record remains in
SecureStore until the best-effort legacy revoke confirms (or the legacy
cleanup policy explicitly clears it).

The exact cleanup rule is therefore:

```text
v1 register success: retain legacy credentials while legacy revoke is pending.
legacy revoke confirmed (including already-missing): clear legacy aliases.
legacy revoke transient failure: retain aliases for retry/rollback.
v1 revoke: clear v1 aliases only after confirmed completion; legacy aliases
           are handled independently by the legacy revoke result.
```

Server cutover is not performed by this task; it is a separate change with an
explicit rollback plan.

## 7. Temporary compatibility (Phase N)

Keep the complete legacy `EventClient` (register/update/revoke/fetch/ack/pending)
while production remains on 0.20.5. After server cutover and a stability period
it may be removed, along with the files that depend on its legacy endpoint
contract: the legacy `EventClient`, legacy registration/registry mapping, and
legacy-only request/response models and tests. FCM ingress, event workers,
notification delivery, and shared lifecycle code remain unless separately
proven unused.

AUTO/LEGACY/V1 selection uses an explicit non-mutating probe against
`/api/platforms/hermes_assistant/events`, using `events.pending` with dummy
credentials. It never uses `device.register` as a probe.

| Probe result | Meaning |
|---|---|
| 503 `platform_unavailable` / `platform_http_events_unsupported` | Plugin absent |
| 404 | No v1 route; use legacy |
| 401 | Plugin/auth handler present |
| HTTP 200, any envelope | Plugin present |
| Network failure | Fail safe to LEGACY until next probe |

Cache the probe in memory and timestamp it in `PushPrefs`, with at most one
probe per five minutes. There is no user UI for this choice.

## 8. Upgrade/downgrade during enrollment

On server downgrade, v1 returns `unsupported_protocol` or 503; capability probe
then returns to LEGACY. On an app upgrade mid-enrollment, persisted
`registration_state` and `push_protocol` self-heal because state is reread
inside the lifecycle lock. A partial enrollment (device ID without secret)
loads as null and fresh-registers.

App backup is disabled (`allowBackup=false`), and SecureStore's
`device_secret` is erased on restore. Restoration consequently yields an
incomplete enrollment and performs a clean fresh registration.
