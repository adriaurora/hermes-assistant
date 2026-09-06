# Reconciliation test plan

The existing suites map the requested 40 cases as follows. “Exist.” means covered by the
listed main/v1 tests; “Nuevo” identifies the reconciliation gap to create.

| # | Case | Coverage |
|---:|---|---|
| 1 | solo ID migration | Exist. `DeviceRegistryMigrationTest` |
| 2 | id+endpoint LegacyPending | Exist. `DeviceRegistryMigrationTest` |
| 3 | complete legacy migration | Exist. `DeviceRegistryMigrationTest` |
| 4 | partial V1 is empty | Exist. `DeviceRegistryV1Test` |
| 5 | secret-before-ID crash | Exist. `SecureStoreDeviceSecretTest` |
| 6 | secret encrypted | Exist. `SecureStoreDeviceSecretTest` |
| 7 | redact toString | Nuevo reconciliación |
| 8 | clear purges secret | Exist. `FcmRevokeCleanupTest` |
| 9 | clear is idempotent | Exist. `FcmRevokeCleanupTest` |
| 10 | pending clear survives death | Exist. `PushLifecycleStateTest` |
| 11 | enable while revoke | Exist. `PushLifecycleStateTest` |
| 12 | enable while clear | Nuevo reconciliación |
| 13 | revoke KEEP | Nuevo reconciliación |
| 14 | revoke under lock | Exist. `FcmLifecycleTest` |
| 15 | empty revoke cleanup | Nuevo reconciliación |
| 16 | V1 revoke snapshot origin | Nuevo reconciliación |
| 17 | V1 revoke snapshot API key | Nuevo reconciliación |
| 18 | V1 revoke snapshot secret | Nuevo reconciliación |
| 19 | device_not_found success | Exist. `V1PolicyTest` |
| 20 | device_revoked success | Exist. `V1PolicyTest` |
| 21 | device_auth_failed terminal | Exist. `V1PolicyTest` |
| 22 | other RPC code retries | Nuevo reconciliación |
| 23 | HTTP/network revoke retry | Exist. `RpcRetryPolicyTest` |
| 24 | legacy revoke dispatch | Exist. legacy worker tests |
| 25 | no register during revoke V1 | Nuevo reconciliación |
| 26 | no register during clear V1 | Nuevo reconciliación |
| 27 | token rotation during revoke | Nuevo reconciliación |
| 28 | V1 token update | Exist. `V1PolicyTest` |
| 29 | reenroll once | Exist. `V1PolicyTest` |
| 30 | stale legacy fallback only 404 | Exist. main ingress tests |
| 31 | non-404 is retryable | Nuevo reconciliación |
| 32 | V1 without secret fresh register | Exist. `DeviceRegistryV1Test` |
| 33 | migration does not claim legacy ID | Exist. `V1PolicyTest` |
| 34 | rollback LEGACY retains V1 creds | Nuevo reconciliación |
| 35 | AUTO probe is non-mutating | Exist. `PushTransportTest` |
| 36 | 200 ok:false classification | Exist. `EventRpcClientTest` |
| 37 | protocol version validation | Exist. `FcmPayloadParserProtocolVersionTest` |
| 38 | persistent delivered dedup | Exist. `DeliveredEventLogTest` |
| 39 | event get/ack/pending delivery | Exist. `EventDeliveryTest` |
| 40 | lifecycle gate and startup recovery | Exist. `FcmLifecycleTest` / `PushGateTest` |

Existing inventory: main `DeviceRegistryMigrationTest` (9), `FcmRevokeCleanupTest` (3),
`PushLifecycleStateTest` (8), `PushPrefsStateTest` (2); v1 `EventRpcClientTest` (28+),
`RpcRetryPolicyTest`, `PushTransportTest` (15), `DeviceRegistryV1Test` (6),
`SecureStoreDeviceSecretTest` (4), `DeliveredEventLogTest` (11),
`FcmPayloadParserProtocolVersionTest` (7), `V1PolicyTest` (12), `FcmLifecycleTest` (1),
`PushGateTest` (12), `EventDeliveryTest` (11), etc. New tests above must use fake clients
and assert no mutation, exact snapshots, durable flags, and lock serialization.
