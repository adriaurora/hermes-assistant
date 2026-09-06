# Combined design

**Status:** COMPLETE (branch `reconcile/fcm-hardening-wire-v1`)
**Closure date:** 2026-09-06

## 1. Máquina de estados combinada

| enabled | protocol | transport (auto-resuelto) | registry (`RegistryState` + secret?) | registration_state | pendingRevoke | pendingCredentialClear | estado lógico |
|---|---|---|---|---|---|---|---|
| false | none/LEGACY/V1 | cualquiera | Empty | DISABLED | false | false | DISABLED |
| true | LEGACY | LEGACY | LegacyPending | REGISTERING | false | false | LegacyPending |
| true | LEGACY | LEGACY | Registered | REGISTERED | false | false | LegacyRegistered |
| true | V1 | V1 | Empty | REGISTERING | false | false | V1Enrolling |
| true | V1 | V1 | Registered + secret | REGISTERED | false | false | V1Registered |
| true | cualquiera | resuelto | Registered | REGISTERING (token persistido) | false | false | TokenUpdatePending |
| false/true | cualquiera | resuelto | cualquiera | UNREGISTERING | true | cualquiera | RevokePending |
| false/true | cualquiera | resuelto | cualquiera | cualquiera | false | true | CredentialClearPending |
| true | cualquiera | resuelto | cualquiera | ERROR | false | false | Error |

Invalid/self-healing combinations: (a) V1 + Registered sin `deviceSecret` →
`EnrollmentPolicy→RegisterFresh` en próximo token sync; (b) secret alias sin `device_id`
(crash post-saveV1 parcial) → `loadOrMigrate=Empty`; blob huérfano se sobrescribe por
próximo `saveV1` o `clear()`; (c) pendingRevoke sin registro → revoke worker
`Empty→FcmRevokeCleanup`; (d) pendingCredentialClear zombi → cleanup/LegacyPending
branch/ConnectionEffects lo consume; (e) registered + transport flip (downgrade) → raw
404/405 → PERMANENT→ERROR, creds V1 retenidas, probe próximo arranque; (f) enabled con
pendingRevoke/clear → `enable()` skip enqueue (main).

## 2. DeviceRegistryStore final

`DeviceRegistration{deviceId, pushEndpoint, hermesOrigin, apiKey, deviceSecret: String?=null}`
tiene `toString` redactado. `RegistryState` es `Registered`, `LegacyPending(deviceId,
pushEndpoint)` o `Empty`. `loadOrMigrate(settings)` aplica la lógica main verbatim
(migración all-or-nothing) y transporta `deviceSecret` si existe. `save()` y `saveV1()`
persisten registro; `saveV1` escribe `device_secret` **ANTES** de `device_id`. `clear()`
borra los cinco aliases, incluido `device_secret`. `updateCredentials(apiKey)` no reescribe
origin ni secret.

## 3. Effects, cleanup y workers

`FcmConnectionEffects` (código main verbatim) funciona igual con V1: Registered lleva
secret y pinning/clear son protocol-agnostic; dispatch LEGACY/V1 ocurre en revoke worker.
Probe nunca muta registry. `FcmRevokeCleanup` (main verbatim) es idempotente y ahora
`registry.clear()` purga también `device_secret`.

`FcmRevokeWorker` usa schedule KEEP, backoff exponencial 30s y lock `FcmLifecycle`. Bajo
el lock: guard `pendingRevoke→success`; Empty→`FcmRevokeCleanup.onComplete`; LegacyPending
ejecuta main verbatim (clear+token si pendingCredentialClear, flags y re-register si enabled).
Registered dispatcha `EventClient(r.hermesOrigin,r.apiKey,r.deviceId).revokeDevice()` en
LEGACY o `EventRpcClient(r.hermesOrigin,r.apiKey,r.deviceId,r.deviceSecret).revoke()` en V1.
`RevokeV1Policy` mapea a `FcmRevokePolicy.RevokeOutcome`: null→RevokeSuccess;
`device_not_found|device_revoked`→RevokeSuccess; `device_auth_failed`→CredentialRejected
(M11: purge local + unblock, orphan remoto documentado); cualquier otro `rpcCode` no null,
OTHER o NETWORK/HTTP sin envelope→RetryAgain. Terminal (RevokeSuccess|CredentialRejected)
llama cleanup compartido; RetryAgain→UNREGISTERING + `Result.retry()` sin tope (backoff
acota coste). `V1Policy.RevokeAction` se elimina/sustituye por este mapeo.

`FcmTokenWorker` conserva fast-path main (enabled/pendingRevoke/pendingCredentialClear→
success); v1 DISABLED/REGISTERED/UPDATED→success; RETRYABLE hace recheck main y retry
si `runAttemptCount<2`, luego failure; PERMANENT falla tras recheck (disabled/clear→success).
`FcmPushRegistrar` conserva triple-check en fast-path y bajo lock; expone
`registerCurrentTokenOutcome:TokenSyncOutcome` y wrapper Boolean; registerToken marca
ENABLED/ERROR/REGISTERING. `FcmLifecycle` es main verbatim: enable skip pending, disable
igual en ambas ramas, cancel mediante `FcmTokenRegistration.WORK_NAME`.

## 4. PushIngress y resolución

Conserva `loadOrMigrate`/preconditions main y añade `resolveTransport`, `legacyClient`,
`rpcClient`, `schedulePendingSync`, `scheduleStartupWork`. `onFcmToken` devuelve:
enabled?→DISABLED; pendingRevoke/clear→DISABLED sin op mutante; !configured→PERMANENT.
Empty+LEGACY registra main y guarda settings origin/apiKey/protocol LEGACY; Empty+V1 hace
`registerFresh(RegisterFresh(false))`, `saveV1`, protocol V1, pending sync, REGISTERED.
LegacyPending+LEGACY→DISABLED (espera bind, nunca segundo legacy); +V1 fresh register
para migración, sin revoke legacy (no hay origin/apiKey pinneados ni se inventan). Registered
+LEGACY actualiza con fallback solo 404 confirmado, éxito guarda token manteniendo origin
pinneado, 404 registra fresh en settings origin, otro→RETRYABLE. Registered V1+secret hace
update; REENROLL limpia y hace un fresh. V1 sin secret hace RegisterFresh(true) con snapshot
existing origin/apiKey, nunca settings actuales.

## 5. Resolución de los seis conflictos

| fichero | main conservado | v1 conservado | garantía final |
|---|---|---|---|
| DeviceRegistryStore | M1–M3,M10 | secret/secure save | RegistryState + saveV1 atómico |
| FcmPushRegistrar | M8 | TokenSyncOutcome | triple-check + wrapper |
| FcmRevokeWorker | M7,M11 | RPC revoke/policy | KEEP, snapshot y mapeo común |
| FcmTokenWorker | lifecycle/retry | v1 outcomes | fast-path/recheck |
| PushPrefs | flags persistentes | protocol/transport/dedup | DataStore versionado |
| PushIngress | origin/preconditions | resolver/RPC | dispatch explícito |

## 6. Seguridad y FASE H

El secret vive solo en SecureStore: nunca WorkManager Data/intents/logs; `toString` redacta;
probe usa credenciales dummy y no muta. En FASE H, `ConnectionTransition` main marca
`revokeRequired` solo al cambiar originIdentity; revoca contra A pinneado (LEGACY/V1),
cleanup y registra contra B: nunca hybrid mutation.
