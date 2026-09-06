# Test Plan

## 1. Scope and execution

Tests are JVM unit tests for the RPC boundary, persistence, state machine, deduplication, parser, concurrency, and WorkManager classification. OkHttp responses are exercised with MockWebServer. Add only the test dependency:

```kotlin
testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
```

Run the suite with:

```text
./gradlew :app:testDebugUnitTest
```

## 2. Required cases

| ID | Caso | Fichero de test | Tipo JVM |
|---:|---|---|---|
| 1 | Parseo de envelope RPC válido | `EventRpcClientTest` | unit + MockWebServer |
| 2 | `ok:true` entrega el result esperado | `EventRpcClientTest` | unit + MockWebServer |
| 3 | HTTP 200 con `ok:false` es fallo lógico | `EventRpcClientTest` | MockWebServer |
| 4 | Código de error desconocido clasifica PERMANENT | `RpcRetryPolicyTest` | unit |
| 5 | `protocol_version != 1` devuelve `unsupported_protocol` | `EventRpcClientTest` | unit + MockWebServer |
| 6 | Register nuevo persiste ID y secret | `EventRpcClientTest` / `DeviceRegistryStoreTest` | unit + MockWebServer |
| 7 | SecureStore roundtrip; blob cifrado no contiene plaintext | `SecureStoreTest` | unit Android crypto shim |
| 8 | Crash simulado durante persistencia deja `load() == null` | `DeviceRegistryStoreTest` | unit |
| 9 | Rotación de token usa `device.token.update` v1 | `FcmTokenWorkerTest` | coroutine unit |
| 10 | Register/update concurrentes quedan serializados por lock | `FcmLifecycleConcurrencyTest` | coroutine test |
| 11 | Revoke v1 envía las credenciales correctas | `FcmRevokeWorkerTest` | unit + MockWebServer |
| 12 | Revoke network→retry y `device_not_found`→success | `FcmRevokeWorkerTest` | unit + MockWebServer |
| 13 | `event.get` devuelve evento completo | `EventRpcClientTest` | unit + MockWebServer |
| 14 | Evento ajeno/unknown produce `event_not_found` permanente | `EventRpcClientTest` | unit + MockWebServer |
| 15 | ACK tras delivery exitosa | `PushGateTest` | unit |
| 16 | ACK duplicado es idempotente | `EventRpcClientTest` | unit + MockWebServer |
| 17 | Pending mapea `events` a `HermesEventsPage` | `EventRpcClientTest` | unit + MockWebServer |
| 18 | Dedup memoria y persistente sobrevive “restart” | `NotificationDeduperTest` / `PushPrefsTest` | unit |
| 19 | Restart de proceso repara estados parciales | `PushStateMachineTest` | unit |
| 20 | Probe 404/503 selecciona LEGACY | `TransportSelectorTest` | unit + MockWebServer |
| 21 | Migración legacy hace revoke best-effort, register nuevo y V1 | `PushIngressMigrationTest` | coroutine + MockWebServer |
| 22 | `DeviceRegistration.toString()` no contiene secret; secret solo SecureStore | `RedactionTest` | unit |
| 23 | Secret no aparece en conversación serializada ni preferencias | `RedactionTest` | unit |
| 24 | FCM sin `event_id` o con UUID inválido se descarta | `FcmPayloadParserTest` | unit |
| 25 | FCM `protocol_version="2"` se ignora | `FcmPayloadParserTest` | unit |
| 26 | Payload antiguo sin `protocol_version` se acepta | `FcmPayloadParserTest` | unit |
| 27 | Tabla completa de retry classification | `RpcRetryPolicyTest` | parameterized unit |
| 28 | Push disabled no registra ni actualiza | `PushStateMachineTest` | unit |
| 29 | Credenciales existentes no duplican register; lock preservado | `FcmLifecycleConcurrencyTest` | coroutine + MockWebServer |
| 30 | Tests FCM existentes continúan pasando | `Fcm*Test` existentes | unit |

## 3. Assertions de contrato

Los tests de MockWebServer deben comprobar método, ruta exacta, Bearer, `protocol_version`, operación, límite de 16 KiB y que `ok:false` con HTTP 200 nunca se convierta en éxito. También deben comprobar que ACK de `event_not_found` es éxito, mientras que ese mismo código en `event.get` es `FETCH_PERMANENT`.

La persistencia debe comprobar el orden secreto-antes-que-ID: una excepción inyectada entre ambas escrituras hace que el registro incompleto no sea visible. Las pruebas de redacción inspeccionan `toString`, mensajes de excepción, preferencias y JSON serializado, sin aceptar API key, secret o token FCM.

## 4. Limitaciones

Workers reales, `FcmMessagingService`, Keystore Android y entrega FCM no son completamente testeables en JVM. Se validan en pruebas E2E server-side y en instrumentación Android. El plan JVM cubre sus decisiones puras, sus entradas y sus resultados persistidos; la compatibilidad de los tests FCM existentes debe seguir siendo una condición de aceptación.
