# ⚠️ HISTORICAL: This document describes the legacy device-ID reconciliation process which has been fully removed from the codebase (legacy_reconciled, legacy_device_id, RevokeV1Policy, and legacy enrollment paths deleted). Kept here only for historical reference.

# Threat model

**Status:** COMPLETE (branch `reconcile/fcm-hardening-wire-v1`)
**Closure date:** 2026-09-06

| Asset / boundary | Threat | Mitigation |
|---|---|---|
| `device_secret` | disclosure in storage or diagnostics | encrypted SecureStore; never logs/DataStore/WorkManager Data/intents/UI; redacted `toString` |
| pinned apiKey snapshot | send credential to wrong origin | origin pinning M2/M10; revoke worker snapshots origin/apiKey |
| FCM token | token leak or stale registration | opaque data-only FCM; token update under lifecycle lock |
| bearer principal | unauthorized RPC | API key plus device_id+secret; server-side ownership |
| revoke to wrong server | settings changed during work | snapshot origin/device ID/secret in worker; dispatch protocol-specific |
| incomplete/zombie clear | credentials survive process death | durable `pendingCredentialClear`, consumed in every terminal path |
| register during revoke | duplicate/orphan devices | triple flag check, KEEP policy, lifecycle lock |
| duplicate after restart | repeated notification | `DeliveredEventLog` + `wasDelivered`, idempotent ACK |
| orphan devices | failed migration leaves remote record | document orphan; never claim legacy ID; server cleanup is out of scope |
| REENROLL orphan | v1 token update fails with REENROLL → clear() + fresh register abandons previous device; remote row becomes orphaned because secret is required but no longer valid | explicit code comment; documented in this table and in §6 of the threat model |
| rollback | destroys usable V1 enrollment | additive migration; LEGACY probe retains V1 credentials |
| capability probe | probe creates device or mutates registry | dummy credentials and `events.pending`; probe never calls register and never mutates registry |
| semantic HTTP success | HTTP 200 hides operation failure | always parse envelope; HTTP 200 `ok:false` is failure |
| process death | half-written transition | secret before ID, durable flags, idempotent cleanup, reread under lock |
| cross-device RPC | device accesses another device’s event | server-side ownership and matching `event_id`, authenticated by both credentials |
| exception bodies | secret/token appears in crash report | no logging/interceptor and secret-bearing exception bodies prohibited (F14 ya aplicado) |

Residual risks are bounded by retry/backoff and explicit terminal `CredentialRejected`;
orphan remote devices are documented rather than silently contacted with unpinned
credentials.
