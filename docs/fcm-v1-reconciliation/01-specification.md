# FCM hardening × Wire Protocol v1 — Reconciliation Specification

**Status:** INTEGRATION IN PROGRESS (branch `reconcile/fcm-hardening-wire-v1`)  
**Date:** 2026-09-06

## 1. Objetivo y autoridad

Se reconcilia `main@0415702` (hardening FCM: P0 origin pinning, P1 legacy migration,
clear-saved-key lifecycle, revoke KEEP, `CredentialRejected`, `pendingCredentialClear`)
con `feature/wire-protocol-v1@b2e9558` (RPC único `POST /api/platforms/hermes_assistant/events`,
`device_id+device_secret`, `AUTO/LEGACY/V1`, dedup persistente). La autoridad funcional es:
**main → lifecycle/credentials/legacy; v1 → protocolo/plugin**.

## 2. Inventario de conflicto

| Grupo | Ficheros |
|---|---|
| Intersección exacta | `DeviceRegistryStore.kt` (+26/-25 vs +23/-4), `FcmPushRegistrar.kt`, `FcmRevokeWorker.kt`, `FcmTokenWorker.kt`, `PushPrefs.kt`, `PushIngress.kt` |
| Solo main | `FcmConnectionEffects.kt`, `FcmRevokeCleanup.kt`, `FcmLifecycle.kt`, `FcmRevokePolicy.kt`, `FcmTokenRegistration.kt`, `SettingsStore.kt`, `SettingsScreen.kt` + 4 ficheros de test nuevos |
| Solo v1 | `SecureStore.kt`, `FcmMessagingService.kt`, `FcmEventWorker.kt`, `FcmPendingWorker.kt`, `MainActivity.kt`, `EventClient.kt`, `app/build.gradle` + 5 componentes nuevos + 9 ficheros de test |
| Baseline | main: 120 tests; v1: 205 tests (overlap ~90 preexistentes) |

## 3. Invariantes main (M1–M11)

1. **M1** legacy migration all-or-nothing→`RegistryState`; `loadOrMigrate` solo publica un registro completo.
2. **M2** origin pinning; el `hermesOrigin` persistido gobierna operaciones del registro existente.
3. **M3** clear saved key; cleanup borra credenciales y API key guardada en todos los terminales.
4. **M4** `pendingCredentialClear` sobrevive process death mediante DataStore y se consume en cleanup/connection effects.
5. **M5** `FcmConnectionEffects` centralizado; él decide clear/revoke/register y no cada callback.
6. **M6** `FcmRevokeCleanup` es idempotente.
7. **M7** revoke usa `ExistingWorkPolicy.KEEP`.
8. **M8** no register durante revoke/clear; triple check antes y bajo el lock.
9. **M9** stale device fallback solo ante 404 confirmado (también `IllegalStateException` cuyo mensaje sea `HTTP 404`).
10. **M10** clear usa `savedBaseUrl`, nunca el origin actual de settings.
11. **M11** 401/403 es terminal (`CredentialRejected`), no retry ciego.

## 4. Invariantes v1 (V1–V11)

1. Endpoint único; todas las operaciones usan el RPC indicado.
2. Cada request y envelope lleva `protocol_version=1`.
3. `device_secret` cifrado, nunca en logs/DataStore/WorkManager Data/intents/UI.
4. Selección explícita `AUTO/LEGACY/V1`, con probe no mutante.
5. HTTP 200 + `ok:false` no es éxito: se decodifica el envelope.
6. Enrollment robusto: secret antes que ID, y ambos son necesarios.
7. Token rotation usa `device.token.update` o reenrollment clasificado.
8. Revoke v1 usa snapshot de origin/apiKey/deviceId/secret.
9. `event.get`/`event.ack`/`events.pending` forman la entrega recuperable.
10. `DeliveredEventLog` persistente y `wasDelivered` impiden duplicados tras restart.
11. FCM es opaco: solo transporta `event_id` y versión, nunca contenido.

## 5. Compatibilidad y non-goals

| Transporte | Servidor | Resultado |
|---|---|---|
| AUTO | plugin disponible | V1 |
| AUTO | legacy, route 404/503 | LEGACY |
| LEGACY | cualquier servidor legacy | rutas legacy |
| V1 | plugin | RPC v1 |

No se hace cutover server, ni Sessions API (tampoco model selection, Runs, STT/TTS ni UI).
