# Design — Wire Protocol v1 push client

## 1. Scope and invariants

This design adds protocol v1 beside the existing legacy client. The Android application remains a delivery client: FCM is data-only and contains an opaque `event_id`; event content is fetched over the authenticated RPC endpoint. The namespace is `dk.foss.jarvis`, OkHttp is used directly (no Retrofit), and all JSON uses `HermesJson { ignoreUnknownKeys, encodeDefaults, explicitNulls = false }`.

The v1 endpoint is `POST {baseUrl}/api/platforms/hermes_assistant/events` with `Bearer API_SERVER_KEY`. Every response is an envelope, including semantic failures returned with HTTP 200:

```json
{"ok":true,"protocol_version":1,"result":{}}
{"ok":false,"protocol_version":1,"error":{"code":"...","message":"...","http_status":400}}
```

The request is `{"protocol_version":1,"type":"<op>",...}` and is at most 16 KiB. The client never uses `response.isSuccessful` as the sole success criterion. Real handler statuses are 401, 503, or 500; upstream operation failures are normally represented in the envelope.

## 2. `hermes/EventRpc.kt` (new)

`EventRpcClient` is the single-endpoint v1 implementation of `EventApi`:

```kotlin
const val RPC_PROTOCOL_VERSION = 1
const val RPC_PATH = "api/platforms/hermes_assistant/events"

@Serializable
data class RpcError(
    val code: String,
    val message: String? = null,
    @SerialName("http_status") val httpStatus: Int? = null,
)

@Serializable
data class RpcRegisterResult(
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_secret") val deviceSecret: String? = null,
    val state: String? = null,
    val existing: Boolean = false,
)

@Serializable
data class RpcDeviceStateResult(
    @SerialName("device_id") val deviceId: String? = null,
    val state: String? = null,
    @SerialName("event_id") val eventId: String? = null,
)

class RpcLogicError(
    val code: String,
    message: String?,
    val httpStatus: Int?,
    cause: Throwable? = null,
) : Exception(message, cause)

class EventRpcClient(
    baseUrl: HttpUrl,
    apiKey: String,
    deviceId: String? = null,
    deviceSecret: String? = null,
) : EventApi {
    suspend fun register(
        label: String,
        token: String,
        deviceId: String? = null,
        deviceSecret: String? = null,
    ): Result<RpcRegisterResult>
    suspend fun updateToken(token: String): Result<Unit>
    suspend fun revoke(): Result<Unit>
    suspend fun probe(): Result<Unit>
}
```

Registration sends `device.register` with `label` and `push:{type:"fcm", token}`. A fresh result contains `device_id`, `device_secret`, `state:"active"`, and `existing:false`; registration with credentials is idempotent and returns `device_secret:null` and `existing:true`. `device.token.update` sends `device_id`, `device_secret`, and `push_token`; `device.revoke` sends the two credentials. `event.get` sends `device_id`, `device_secret`, and `event_id`; `event.ack` sends `device_id`, `device_secret`, and `event_id`; `events.pending` sends `device_id`, `device_secret`, and `limit` (the client uses 50; the server caps it at 100).

`fetchEvent()` validates `event_id` and ownership exactly as the legacy client; `pending()` maps `events.pending` to `HermesEventsPage`; `ack()` maps `event.ack` to success and treats `event_not_found` as success. `probe()` sends `events.pending` with `device_id="00000000-0000-0000-0000-000000000000"` and `device_secret="capability-probe"`. It is non-mutating and must never call `device.register`.

Every response is decoded as an envelope. `ok:false` becomes `EventFetchException(kind=HTTP, statusCode=error.http_status, rpcCode=error.code, cause=RpcLogicError(...))`, including when HTTP is 200. An envelope protocol version other than 1 becomes the same failure with `rpcCode="unsupported_protocol"`. HTTP 401/503/500 retain their real status (503 means the plugin may be absent); `IOException` is NETWORK and `SerializationException` is SERIALIZATION, with the same semantics as `classifyFetchFailure`. Response bodies and credentials are never logged.

`EventFetchException` in `hermes/EventClient.kt` gains the compatible field:

```kotlin
val rpcCode: String? = null
```

The legacy client is not changed otherwise. Wire events contain `event_id,event_type,title,body,priority,source,source_id,session_id,state,created_at,available_at,expires_at,delivered_at,acknowledged_at`; unknown keys are ignored, and there is no `device_id` or `status` in the event.

## 3. Error classification and retry

`push/RpcRetryPolicy.kt` (or the equivalent extension of `FcmRetryDecision`) maps protocol errors as follows. Unknown codes are conservatively permanent.

| Class | Codes and failures | Action |
|---|---|---|
| Success | envelope `ok`; ACK `event_not_found` | Complete |
| Retry | NETWORK, timeouts, serialization, 5xx, 503 `platform_unavailable` or `platform_http_events_unsupported` | WorkManager retry, bounded |
| Permanent | `unsupported_protocol`, `unknown_operation`, `invalid_request`, `invalid_push`, `payload_too_large`, `event_not_found` from `event.get`, raw HTTP 404/405 (no envelope — downgrade/route absent) | No retry |
| Re-enroll | `device_not_found`, `device_revoked`, `device_auth_failed` | Clear enrollment as appropriate and perform one fresh register under the lock |

Permanent failures can never loop. Retry is bounded by `MAX_RETRIES` and WorkManager backoff. `device_auth_failed` during revoke does not clear local credentials and moves registration to ERROR.

## 4. Secure registration and models

`data/SecureStore.kt` adds `DEVICE_SECRET_ALIAS = "hermes_device_secret"` and `loadDeviceSecret`, `saveDeviceSecret`, and `clearDeviceSecret`. The existing `KeystoreAeadCipher` remains AES-256-GCM in AndroidKeyStore, storing Base64(`iv || ciphertext`) in `hermes_secure`.

`DeviceRegistration` gains `val deviceSecret: String? = null`. Its `toString()` is redacted and never emits `deviceSecret` or `apiKey`. `saveV1(deviceId,deviceSecret,pushEndpoint,origin,apiKey)` writes the secret first and `device_id` last. Thus a crash before the final write makes `load()` return null and causes a clean re-register. Legacy loading remains backward compatible; complete loading requires device ID, endpoint, origin, and API key, and includes the secret when present.

Optional compatibility fields on `HermesEvent` are `state: String? = null`, `delivered_at: Double? = null`, and `acknowledged_at: Double? = null`.

## 5. Preferences and persistent deduplication

`PushPrefs` adds:

```kotlin
val PUSH_PROTOCOL = stringPreferencesKey("push_protocol") // LEGACY or V1
val PUSH_TRANSPORT = stringPreferencesKey("push_transport") // auto/legacy/v1
val DELIVERED_EVENTS = stringPreferencesKey("delivered_events")
enum class PushProtocol { LEGACY, V1 }
```

`PUSH_PROTOCOL` defaults to LEGACY and is explicit enrollment state; it is never inferred merely from a secret. `PUSH_TRANSPORT` defaults to `auto`. The JSON array in `DELIVERED_EVENTS` is kotlinx.serialization encoded, FIFO bounded to 128 IDs, and provides `recordDelivered(id)` and `wasDelivered(id)`. This is the phase-L persistent dedupe layer; the existing in-memory bounded 1024-entry set (insertion-order eviction) remains in place.

## 6. Transport selection

`push/PushTransport.kt` defines `PushTransport { LEGACY, V1 }` and `TransportSelector`. An explicit preference wins. In `auto`, it calls `EventRpcClient.probe` with the current API key and caches the result in memory, persisting `last_probe_at` and probing at most once per five minutes:

`probe()` returns `Result<Int>`: `success(status)` with the HTTP status code of any response (including `ok:false` envelopes), or `failure` only when the network call itself fails (no response received).

| Probe result | Selection |
|---|---|
| 503 `platform_unavailable` or `platform_http_events_unsupported` | LEGACY |
| HTTP 404 (route absent in 0.20.5) | LEGACY |
| HTTP 401 | V1 (handler exists; auth is configured separately) |
| HTTP 200, including `device_not_found` envelope | V1 |
| Any other status / network failure | LEGACY |

No user UI is required. Selection is resolved per ingress call, while the short cache prevents unnecessary probes.

## 7. Test coverage

Unit tests cover policy and concurrency decisions: `V1PolicyTest` exercises `EnrollmentPolicy` and `RevokeV1Policy` classification rules; `FcmLifecycleTest` verifies that `withLockReturning` serializes concurrent coroutines without overlap. MockWebServer tests validate `EventRpcClient` envelope parsing and `RpcRetryPolicy` error classification. `PushGateTest`, `EventDeliveryTest`, and `PushTransportTest` cover the gate, delivery, and transport selection flows.

## 7. FCM ingress and gate

`FcmPayloadParser` accepts an absent `protocol_version` for old payloads, accepts string `"1"`, and ignores any other version. FCM remains data-only and contains only `event_id` and optionally `protocol_version`.

`PushDeps` gains `onDelivered: (String) -> Unit = {}` and `wasDelivered: (String) -> Boolean = { false }`. After `DeliveryOutcome.SUCCESS`, `PushGate` invokes `onDelivered(eventId)`. A fetch `event_not_found` produces `GateOutcome.FETCH_PERMANENT`, which the worker does not retry. If an in-memory dedupe hit also has persistent `wasDelivered`, the gate attempts idempotent ACK and returns ACKED or ACK_FAILURE. This prevents process-death pending loops.

`EventDispatcher.onPendingSync` similarly ACKs a persistently delivered event without notification; other events use the normal fetch, delivery, and ACK flow.

`PushIngress` resolves transport for every call. For V1 it constructs `EventRpcClient(baseUrl, apiKey, deviceId, deviceSecret)`; for legacy it uses `EventClient`. `onFcmToken` registers when enrollment is absent, or migrates a legacy enrollment when transport is V1 (best-effort legacy revoke, fresh register, saveV1, then `push_protocol=V1`). An existing V1 enrollment updates the token. Any device error clears and performs one fresh register under the same lock. `schedulePendingSync(context)` enqueues unique `"hermes-pending-sync"` with KEEP and CONNECTED.

## 8. Workers and lifecycle

`FcmTokenWorker` uses v1 `device.token.update`, registers when no enrollment exists, and clears/re-registers for a re-enroll result. It has four bounded attempts and exponential 30-second backoff. `FcmRevokeWorker` uses `device.revoke`; success, `device_not_found`, and `device_revoked` clear local state and succeed; `device_auth_failed` leaves state ERROR without an infinite retry; network errors retry up to 5 attempts (bounded); if `deviceSecret` is absent the worker clears and re-registers (secret irrecuperable). `FcmEventWorker` returns failure without retry on `FETCH_PERMANENT`, and schedules pending sync after every completion, success or failure. `FcmPendingWorker` retries failures up to 5 attempts (bounded), and succeeds on a completed sync. `MainActivity.onCreate` schedules startup work (pending sync + re-registration if token is stale), providing reboot/app-update recovery; it is also scheduled after token rotation and every event worker.

| Worker | Unique work | Constraint | Retry/backoff | Permanent outcome |
|---|---|---|---|---|
| Token | `hermes-fcm-token-registration`, REPLACE | CONNECTED | up to 4, exponential 30s | failure / skip if disabled after recheck |
| Revoke | existing revoke work, REPLACE | CONNECTED | up to 5, exponential 30s; clear + re-register if secret absent | success for absent/revoked |
| Event | unique per event, KEEP | CONNECTED | FETCH/DELIVERY/ACK retry policy | `FETCH_PERMANENT` → failure |
| Pending | `hermes-pending-sync`, KEEP | CONNECTED | up to 5, exponential 30s; classify permanent → skip | success on complete |

## 9. Combined state machine

The state is the combination of `enabled`, `pending_revoke`, `registration_state`, and `push_protocol`:

| Logical state | Transition | Single mutating operation |
|---|---|---|
| DISABLED | enable | set REGISTERING, enqueue register |
| LEGACY_REGISTERED | legacy enrollment selected | legacy register/update |
| V1_ENROLLING | enable or re-enroll | `device.register` |
| V1_REGISTERED | register success with secret | persist V1 enrollment |
| TOKEN_UPDATE_PENDING | token persisted, REGISTERING | `device.token.update` |
| REVOKE_PENDING | disable sets pending_revoke | `device.revoke` |
| ERROR | auth or unrecoverable registration error | explicit retry/enable |

`V1_REGISTERED` requires a secret. A V1 protocol with device ID but no secret is treated as not enrolled and re-registers. V1 credentials are not considered operational without V1 transport selection. `pending_revoke=true` blocks new registration until revoke completes.

All register, update, revoke, and migration mutations run through the existing non-reentrant `FcmLifecycle.withLock`/`Returning`. There is at most one server-side mutating operation per logical device. An in-flight register finishes before disable; the revoke worker reads state inside the lock. Lock nesting is prohibited.

## 10. Security and compatibility

`device_secret`, API key, and FCM token are SecureStore values; no secret, token, or API key is logged. `allowBackup=false`, release cleartext policy, and BYO Firebase behavior remain unchanged. No `HttpLoggingInterceptor` is introduced, and builds without `google-services.json` still compile.

## 11. Reconciliation with FCM hardening (main)

This design reconciles with the FCM hardening on main (branch `reconcile/fcm-hardening-wire-v1`; full design at `docs/fcm-v1-reconciliation/02-design.md`). Key adopted points:

- **`RegistryState` / legacy migration**: `loadOrMigrate` publishes a single complete record through `RegistryState` (Registered / LegacyPending / Empty).
- **Origin pinning**: the persisted `hermes_origin` governs all operations for an existing device, never overwritten by settings changes.
- **`pendingCredentialClear`**: survives process death via DataStore; consumed by connection effects and cleanup.
- **Revoke KEEP + CredentialRejected**: revoke uses `ExistingWorkPolicy.KEEP` with exponential back-off (30 s initial); transient retry without local cap; 404 = idempotent success; 401/403 → `CredentialRejected` (purge all local copies + unblock).
- **`legacy_device_id`**: the `device.register` body now accepts an optional `legacy_device_id` field (snake_case wire). When present on a fresh registration, the server supersedes the imported legacy row. Requires plugin ≥ f4670a1; older plugins ignore the unknown field.
