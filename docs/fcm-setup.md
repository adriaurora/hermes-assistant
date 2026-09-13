# Optional FCM push setup

Chat and voice are Sessions API only. FCM is only a wake transport for durable
Hermes events. Push is optional and uses the `hermes_assistant` plugin Wire
Protocol v1; it is not a second chat or voice service.

## Android configuration

The Android namespace is `dk.foss.jarvis`. Release uses
`io.github.adriaurora.hermesassistant`; debug uses
`io.github.adriaurora.hermesassistant.debug`.

Put Firebase files at the matching paths:

```text
app/src/debug/google-services.json    # .debug client
app/src/release/google-services.json  # release client
```

`app/google-services.json` may contain both clients. Do not commit these
files. A client must match the variant application ID exactly.

With no matching JSON in the root, debug, or release locations, the Google
Services plugin is not applied. The project still builds and the app explicitly
reports push unavailable; chat and voice continue to work. Remove all three
locations for a deliberate no-Firebase build.

## Server and credentials

Install and enable the `hermes_assistant` plugin on the same Hermes `api_server`
configured in Settings. **TODO:** the plugin repository and canonical install
command are not available in this checkout; add them when the upstream source
is available. Do not invent a URL.

Use the existing Hermes API-server Bearer key. The server must authenticate it,
hold Firebase/FCM server credentials as deployment secrets, and publish a
data-only wake containing only an opaque `event_id` (optionally
`protocol_version`). Android stores the returned `device_id` and one-time
`device_secret` only in its AndroidKeyStore-backed secure store. Never commit
API keys, service-account keys, device secrets, or FCM tokens.

## Plugin/RPC operations

All operations are POSTed to:

```http
{baseUrl}/api/platforms/hermes_assistant/events
Authorization: Bearer <Hermes API-server key>
Content-Type: application/json
```

Requests contain `protocol_version: 1` and `type`. Responses are envelopes:
`{"ok":true,"protocol_version":1,"result":{...}}` or an equivalent error
envelope. Decode the envelope even on HTTP 200; `ok:false` and
`error.http_status`/`error.code` are failures.

| RPC type | Data | Operation |
|---|---|---|
| `device.register` | `label`, `push:{type:"fcm",token}` | Create/recover a device; fresh registration returns `device_id` and `device_secret` once. |
| `device.token.update` | `device_id`, `device_secret`, `push_token` | Update a rotated token. |
| `device.revoke` | `device_id`, `device_secret` | Revoke; 404 is already revoked. |
| `event.get` | `device_id`, `device_secret`, `event_id` | Fetch and mark delivered. |
| `event.ack` | `device_id`, `device_secret`, `event_id` | Idempotently acknowledge. |
| `events.pending` | `device_id`, `device_secret`, `limit` (max 100) | Recover delivered-but-unACKed events. |

Availability probing uses non-mutating `events.pending` with dummy credentials;
it never calls `device.register`. Mutations are serialized per device. Clear
saved key disables push, revokes with the pinned origin and credentials, then
purges local credentials on success or 404.

## Narrow `http_event_auth_mode` seam

If the server has `http_event_auth_mode`, it is only a compatibility seam for
authentication of `/api/platforms/hermes_assistant/events`. It must not alter
Sessions API authentication, chat/voice routing, application IDs, or FCM
payloads. Android always sends the Bearer API-server key plus
`device_id`/`device_secret` in RPC bodies. Prefer strict plugin mode and verify
any compatibility mode cannot weaken other routes.

## Installation agents: 10 requirements

1. Verify the server URL is reachable from the phone.
2. Verify the Bearer key with a normal server check; do not expose it.
3. Obtain the plugin from its canonical upstream source (TODO above).
4. Enable the plugin and Wire Protocol v1 route.
5. Configure server-side Firebase credentials as secrets.
6. Verify `http_event_auth_mode`, if present, is route-scoped.
7. Install variant-matching `google-services.json` at the paths above.
8. Build and verify FCM availability (and no-config behavior when applicable).
9. Install, grant Android 13+ notifications, configure Hermes, and enable push.
10. Complete every verification step below, including disable and clear-key.

## Full verification

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
```

Verify that Sessions chat creates and resumes one server session, voice uses
that same session, and unsupported Sessions capability is surfaced rather than
downgraded. Then verify:

1. Enable push; registration persists both device credentials.
2. Rotate the token; `device.token.update` succeeds.
3. A test FCM body contains only opaque ID/protocol version, never content.
4. `event.get` produces exactly one notification and `event.ack` succeeds.
5. Repeated wake/process death is recovered by `events.pending` without a duplicate.
6. Offline fetch retries and delivers after connectivity returns.
7. Disable push; revoke retries transient failures and accepts 404.
8. Clear saved key; API key, device ID, token, origin, and secret are purged.
9. A no-config build still supports chat/voice and explicitly disables push.
10. Logs, APK resources, and history contain no credentials, secrets, or tokens.

See [Wire Protocol v1](wire-protocol-v1/) for the detailed protocol and
[Sessions API chat](sessions-api-chat.md) for chat architecture.
