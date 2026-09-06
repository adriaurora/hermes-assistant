# Migration strategy

**Status:** COMPLETE (branch `reconcile/fcm-hardening-wire-v1`)
**Closure date:** 2026-09-06

## 1. Git strategy

Create `reconcile/fcm-hardening-wire-v1` from `feature/wire-protocol-v1`, merge
`origin/main@0415702`, and resolve each file according to `02-design`. No force-push.
`feature/wire-protocol-v1` is preserved; merge to main only after all green gates.

Rollback leaves main intact and feature/wire-protocol-v1 intact: this reconciliation is
additive, so rollback is simply abandoning this branch.

## 2. On-disk migration

| On disk | `loadOrMigrate` | EnrollmentPolicy |
|---|---|---|
| solo-id | incomplete → Empty | RegisterFresh |
| id+endpoint | LegacyPending | wait for bind / legacy registration |
| complete legacy | Registered, no secret | LEGACY; V1 discovery does fresh register |
| V1 with secret | Registered + secret | V1 update |
| partial V1 (secret alias only, or id without secret) | Empty (or incomplete) | RegisterFresh; orphan blob overwritten by saveV1/clear |

The all-or-nothing rule is preserved: a crash cannot make incomplete credentials
operational.

## 3. LEGACY→V1 (FASE I)

LegacyPending/LegacyRegistered discovering the plugin performs fresh `device.register`;
it never claims the legacy device and never invents origin. Legacy revoke is best-effort
**only with a pinneado snapshot when one exists**. Then `saveV1` and `protocol V1`.
On rollback, `probe/push_transport` selects LEGACY and retains V1 credentials for a future
retry; rollback does not destroy them.

## 4. Crash safety and ordering

`saveV1` writes secret before device_id. DataStore flags (`pendingRevoke` and
`pendingCredentialClear`) are durable. Cleanup is idempotent and repeats safely after
process death. Every mutating transition is under the non-reentrant lifecycle lock;
registration is blocked while either pending flag is set.

## 5. Gates

Run both baselines (main 120 and v1 205, acknowledging ~90 overlap), all reconciliation
tests, lint, and assemble. Inspect that no secret occurs in logs, intents, WorkManager
Data, or UI. Only then merge; never cut over the server in this change.

### 5.1 Final results (2026-09-06)

All reconciliation gates are closed:

- 267 JVM tests PASS (0 failures); M1–M11 (main FCM hardening) and V1–V11
  (Wire Protocol v1) preserved and PASS.
- `lintDebug` PASS (0 errors).
- `assembleDebug` PASS both Firebase-free and Firebase-configured.
- `assembleRelease` PASS and `lintVitalRelease` PASS.
- Working tree clean; production server untouched.

The resulting code is `READY_FOR_ANDROID_RC`. Server cutover remains out of scope.
