# Hermes Assistant Android — Wire Protocol v1 (plugin hermes_assistant) — Specification

**Status:** IMPLEMENTED IN PROGRESS (branch `feature/wire-protocol-v1`)  
**Date:** 2026-09-06

## 1. Scope / Goals

This specification migrates Android push/event delivery from the legacy contract
(`/api/devices/*`, `/api/events/*`, and `device_id` only) to the independent
`hermes_assistant` plugin Wire Protocol v1 (`device_id` + `device_secret`). It
validates the complete stack: modern Hermes upstream, the `hermes_assistant`
plugin, Hermes Assistant Android, and BYO Firebase, without depending on changes
to the legacy core FCM/event inbox.

## 2. Non-goals

This work does not migrate the Sessions API, add model selection, implement
`session_model_clear`, migrate the Runs API, add STT/TTS, make visual changes,
or cut over the server. The server remains **READY_FOR_SERVER_CUTOVER** at
most.

## 3. Source of truth and HTTP contract

The source of truth is `/opt/services/hermes-assistant-plugin/docs/protocol-v1.md`,
read over SSH from server `192.168.88.31`.

The plugin exposes one operation endpoint:

```http
POST {baseUrl}/api/platforms/hermes_assistant/events
Authorization: Bearer <API_SERVER_KEY>
Content-Type: application/json

{"protocol_version":1,"type":"<op>",...}
```

The same API key currently used by the app as its Bearer credential is used.
Requests are limited to 16 KiB. Every response is an envelope:

```json
{"ok":true,"protocol_version":1,"result":{...}}
{"ok":false,"protocol_version":1,"error":{"code":"...","message":"...","http_status":N}}
```

**Known limitation (Phase C):** the upstream handler returns HTTP 200 even
when the internal operation fails; the semantic error is in
`error.http_status`. `response.isSuccessful` must never be the sole criterion:
the envelope is always parsed. Real handler-level HTTP errors also exist,
including 401 (auth handler), 503 (`platform_unavailable` or
`platform_http_events_unsupported`), and 500.

## 4. Legacy → v1 operation matrix

| Android action | Legacy | Plugin v1 |
|---|---|---|
| register | `POST /api/devices/register {push_type:"fcm",push_token,device_id?}` → `{device_id,status}` | `device.register {"label","push":{"type":"fcm","token"}}` → `{device_id, device_secret (once, only for new registration), state:"active", existing:false}`; with `device_id+device_secret` supplied → `{device_id,state,label,device_secret:null,existing:true}` (idempotent); unknown device → `device_not_found` 404; wrong secret → `device_auth_failed` 403 |
| token update | `POST /api/devices/{id}/token` | `device.token.update {device_id,device_secret,push_token}` → `{device_id,state}` |
| revoke | `DELETE /api/devices/{id}` | `device.revoke {device_id,device_secret}` → `{device_id,state:"revoked"}` |
| fetch event | `GET /api/events/{id}` | `event.get {device_id,device_secret,event_id}` → complete event (**side effect:** marks `state=delivered`) |
| ACK | `POST /api/events/{id}/ack` | `event.ack {device_id,device_secret,event_id}` → `{event_id,state:"acked"}` (idempotent; ACK of an already ACKed event → `ok:true`) |
| pending | `GET /api/events?status=pending&device_id=...` | `events.pending {device_id,device_secret,limit<=100}` → `{"events":[...]}`, ordered by `available_at,event_id`; includes delivered but not ACKed events (FCM-loss recovery) |

## 5. Real schemas

Schemas below are verified in the plugin `store.py`.

### 5.1 Event wire schema

```json
{
  "event_id":"...", "event_type":"...", "title":"...", "body":"...",
  "priority":"low|normal|high", "source":"...", "source_id":"...",
  "session_id":"...", "state":"pending|push_attempted|push_sent|delivered|acked|expired|failed",
  "created_at":"...", "available_at":"...", "expires_at":"...",
  "delivered_at":"...", "acknowledged_at":"..."
}
```

It includes neither `device_id` nor the legacy `status` field. The existing
Android `HermesEvent` is compatible: required `event_type`, `created_at`, and
`available_at` are present; `EventPrioritySerializer` already supports string
priority, and `HermesJson` ignores extra fields.

### 5.2 Registration and errors

A new registration returns `{device_id,device_secret,state,existing}`. The
secret is `secrets.token_urlsafe(32)` (256 bits), returned only once. The server
stores only its scrypt hash (`n=2^14,r=8,p=1`, with a 16-byte salt).

Defined errors are `unsupported_protocol` (400), `unknown_operation` (404),
`invalid_request` (400), `payload_too_large` (413), `invalid_push` (400),
`device_not_found` (404), `device_revoked` (409), `device_auth_failed` (403),
and `event_not_found` (404). Ownership failures use not-found where possible.

## 6. Security change

Authentication now requires `device_id` plus `device_secret`. The server
generates the 256-bit secret once, stores only its scrypt hash, and Android
stores the credential securely. This removes the legacy model in which knowing
`device_id` plus `API_SERVER_KEY` was sufficient.

## 7. FCM semantics

FCM remains data-only and contains no content:

```json
{"event_id":"...","protocol_version":"1"}
```

Android performs FCM → `event.get` (with both credentials) → content → native
notification → `event.ack`. A fallback that displays content directly from the
push is prohibited.

## 8. Relevant server behavior

An invalid FCM token (`UNREGISTERED`) causes the plugin to revoke the device
(`state=revoked`); Android treats `device_revoked` as a re-enrollment signal.
A legacy-imported device is `legacy_pending_enrollment` and cannot claim a
secret using its old `device_id` (there is no push-token matching). Re-enrollment
is always a fresh `device.register`, producing a new ID and secret. The legacy
device becomes server-side orphaned; cleanup is out of scope.

## 9. Acceptance criteria (Phases D–T)

- [ ] Registration is incomplete until **both** credentials are persisted.
- [ ] HTTP 200 with `ok:false` is treated as failure using its error code.
- [ ] `push_protocol` is explicit and persisted (`legacy|v1`).
- [ ] LEGACY/V1 transition is controlled by an explicit probe; `device.register`
      is never a health probe.
- [ ] Each state transition permits at most one concurrent mutating server
      operation per device.
- [ ] v1 errors map to success, retry, or permanent-failure classes.
- [ ] ACK is idempotent.
- [ ] One `event_id` produces no duplicate notification, including after process
      death.
- [ ] `device_secret` exists only in SecureStore (AndroidKeyStore AES-256-GCM),
      never logs, analytics, UI, or plaintext DataStore.

## 10. Target compatibility matrix

| Android | Hermes/server | Target behavior |
|---|---|---|
| New | Hermes legacy 0.20.5 | Legacy (probe fails → legacy client) |
| New | Modern upstream without plugin | Legacy (503 `platform_unavailable` / 404 → legacy; legacy routes remain in the same upstream commit `b3531b8`) |
| New | Modern upstream + plugin | v1 |

BYO Firebase remains supported: `google-services.json` is variant-specific,
and builds without it remain supported.
