# Sessions API chat (archival — not operational)

> **ARCHIVAL — NOT OPERATIONAL.** This file is retained only as historical context for an earlier migration design. It is not a current contract, must not be used to implement or operate the client, and must not be used as guidance for older endpoints.

**Status:** HISTORICAL

## 1. Overview

The chat architecture is: Android conversation → persistent Hermes session →
Sessions API streaming → capability-driven model selector → Automatic ↔ explicit
model, Sessions API chat as the sole supported conversation transport.

## 2. Capability negotiation

Before selecting a transport, the client requests `GET /v1/capabilities` and
decodes `CapabilitiesEnvelope` into `ServerFeatures`. The relevant flags are
`session_chat`, `session_chat_streaming`, `session_model_lock`,
`session_model_clear`, and `model_options`.

`CapabilityRegistry` caches `OriginCapabilities` by normalized origin. A
successful response is `SUPPORTED`; HTTP 404 or 405 means `UNSUPPORTED`, which
is cached and blocks continuation. HTTP 401/403, 5xx, network failures, and
timeouts mean `UNKNOWN`: they are never cached, never downgrade to an older transport, and
block sends with a retry message instead. Modern endpoints are used only when
`session_chat` is true. Streaming is preferred when `session_chat_streaming` is
true. The model selector requires both `model_options` and
`session_model_lock`. Returning a locked session to Automatic requires
`session_model_clear`.

## 3. Conversation ↔ session binding

The persisted `Conversation` fields are `transport: ChatTransportKind?`,
`origin` (the v2 identity: normalized full base URL, including path, plus a
SHA-256 fingerprint of the API key), `sessionId`, and `lastUsedAt`. Older
`scheme://host:port` origins are retained as legacy data and are never claimed
locally; a Sessions conversation is rebound only after an authenticated GET of
its session succeeds.
`ChatTransportKind` contains only `SESSIONS`. A session created on
origin A is never sent to origin B; origin isolation is enforced by
`ChatTransportSelector`.

Chat session identity is independent of FCM `device_id`, `legacy_device_id`,
event IDs, and the push protocol. `ChatTransportKind` is deliberately not
`PushProtocol`.

## 4. Migration rules

Existing (pre-migration) conversations remain `LEGACY_CHAT` forever. An
undecided conversation with messages is treated as Legacy. A brand-new
conversation becomes `SESSIONS` when capabilities allow it. Undecided
conversations never mix transports. A brand-new conversation commits to `SESSIONS`
when capabilities allow it; if create-session fails, it remains `SESSIONS`-bound
with no server session and the unsent user turn is retained locally. It is
submitted only by an explicit user retry, and then exactly one turn is sent.
There is no orphan server session and no downgrade to an older transport.

## 5. Session lifecycle

Sessions are created lazily on the first message with
`POST /api/sessions` and body `{"title":"..."}`. The binding is persisted
before the first turn. The client can read a session with
`GET /api/sessions/{id}`, hydrate history with
`GET /api/sessions/{id}/messages?order=oldest`, and delete it with
`DELETE /api/sessions/{id}`.

The initial title is derived from the first user turn: whitespace is collapsed,
the result is capped at 60 characters, and blank text falls back to
`Conversation`. On a duplicate-title rejection (HTTP 400/409), the client retries
once with a deterministic ` · <conversation-id prefix>` suffix. Session identity
is always the server-generated `session.id`; the title is never used as identity.

The server owns history. Each turn sends only the new turn,
`{"message":"..."}`; history is never resent. On open, the local mirror is
replaced, not concatenated, with the oldest-first server transcript. If the
fetch fails, the saved local copy is shown with a notice.

## 6. Resume & missing session

After a force-stop/cold start with no persisted active-conversation pointer, the
app starts a new conversation; restoring the last active conversation is tracked
as backlog.

After an app restart, `origin` and `sessionId` are recovered from disk and the
same session is resumed. A `404 session_not_found` never falls back to legacy.
It surfaces “Remote session no longer exists… start a new conversation” and
blocks sends for that conversation. Recovery is explicit: the user starts a
new conversation; the client does not silently replace the session.

## 7. Streaming

When supported, turns use `POST /api/sessions/{id}/chat/stream` over SSE via
`okhttp-sse`. The `HermesClient.StreamCallbacks` mapping is:

| SSE event | Client action |
|---|---|
| `run.started`, `message.started` | Ignore |
| `assistant.delta` | Emit delta |
| `assistant.completed` | Final content replaces accumulated deltas, without duplication |
| `tool.started`, `tool.progress` | Mark tool running |
| `tool.completed`, `tool.failed` | Mark tool done |
| `run.completed` | Emit runtime and completion |
| `done` | Completion |
| `error` | Terminal error; keep the session and do not auto-resend |

Keepalive comments are ignored. The SSE client handles fragmented frames and
multiple events in one read. The non-streaming
`POST /api/sessions/{id}/chat` fallback is used only when
`session_chat_streaming` is false.

## 8. Model selector

`GET /api/model/options` is the only source for model options. `/v1/models`
remains only the Settings connection test. Selection is persistent per session
through `POST /api/sessions/{id}/model` with exactly
`{"model":"<string id>"}` and no provider. Clearing uses exactly
`{"model":null}`—no provider and no other keys—and is sent only when
`session_model_clear` is true.

The UI confirms a selection only after HTTP 200. Clearing additionally verifies
that `session.model` is null or empty. Rejected selections never update the
confirmed state. If clearing is unsupported while a lock exists, Automatic is
disabled with an explanation. A new conversation is the route back to automatic
routing; there is no fake clearing or silent replacement session.

## 9. Selected vs effective

The selector displays the **selected override** (`session.model` / last server
acknowledgement). The **effective** model for a turn comes from
`runtime.model`; its routing reason comes from `runtime.route_source`:
`global`, `raw_request`, `model_routes`, or `session_model_lock`. Both values
are surfaced separately in the model sheet. In summary, server precedence is:
confirmed session lock → internal override → `sessions.model` →
`model_routes` alias → per-request → global default. Chat turns never carry a
model. The model-lock POST response echoes the resolver's pre-turn state and may report an intermediate `route_source` (for example `raw_request`); the authoritative route for a turn is the `runtime` carried by that turn's `run.completed` event, which reports `session_model_lock` when a confirmed lock is in effect.

## 10. Legacy fallback rules

`/v1/chat/completions` remains for `LEGACY_CHAT` conversations. The only
permitted fallback triggers are capabilities being `UNSUPPORTED` because of
404/405, or capabilities reporting `session_chat=false`.

There is never a fallback for 401/403 (`gateway_auth_failed`), 5xx, timeouts,
network failures, `session_not_found`, stream failures, or model errors. A
temporary failure is not evidence that the Sessions API is unsupported. 401/403
are surfaced as credential problems.

## 11. Security notes

Secrets remain in the AndroidKeyStore-backed `SecureStore`. All modern
endpoints use `Authorization: Bearer`. No secret is logged or persisted in
plaintext. Session IDs are safe to persist, but remain origin-scoped.

## 12. Test map

- `CapabilitiesJsonTest`: capability envelope and feature decoding.
- `CapabilityRegistryTest`: per-origin states, caching, and UNKNOWN behavior.
- `SessionWireTest`: session endpoint paths and turn payloads.
- `SessionSseParserTest`: event mapping, fragmented frames, and completion.
- `ModelBodyContractTest`: model request shape, including byte-exact
  `{"model":null}` clear body.
- `RuntimeInfoDecodeTest`: runtime model and route-source decoding.
- `TransportSelectionTest`: capability and origin transport decisions.
- `ConversationTransportMigrationTest`: persistence and migration invariants.
- `LegacyChatRegressionTest`: retained `/v1/chat/completions` behavior.
- `ModelSelectionTest`: selection, confirmation, and clear behavior.
- `SessionsMockE2eTest`: lifecycle, hydration, streaming, and failure paths.
