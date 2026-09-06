# Threat Model — Wire Protocol v1

## 1. Assets and mitigations

| Asset | Threat | Mitigation |
|---|---|---|
| `API_SERVER_KEY` (bearer) | Exposición en logs, preferencias o backup | SecureStore; nunca DataStore/BuildConfig/logs; excepciones solo con status; `allowBackup=false`. |
| `device_id` | Correlación o uso con credenciales incompletas | No es secreto suficiente; V1 exige también `device_secret`; estado incompleto se re-registra. |
| `device_secret` (256 bits, una vez) | Aparición en logs o `toString()` | `toString()` redactado, sin interceptor de logging, errores sin cuerpos ni credenciales; test de redacción. |
| `device_secret` | Plaintext en disco | SecureStore con AndroidKeyStore, `KeystoreAeadCipher`, AES-256-GCM y blob Base64(`iv||ct`); nunca DataStore/SharedPreferences plaintext; `allowBackup=false`. |
| FCM token | Rotación perdida o copia en logs | SecureStore; persistir al llegar, worker REPLACE, re-register en `device_*`; no logging. |
| Contenido de eventos | Exfiltración por FCM o logs | FCM data-only con solo `event_id` y `protocol_version`; contenido únicamente tras fetch autenticado; parser descarta extras. |
| API envelope | HTTP 200 `ok:false` tratado como éxito | Parseo obligatorio del envelope en cada respuesta; tests MockWebServer cubren 200 semántico. |
| Registro de device | Register duplicado u orphan devices | Idempotencia con credenciales y `FcmLifecycle` lock; secret se persiste antes del ID. Un crash después del register y antes de persistir puede dejar un huérfano server-side: se acepta y se documenta; el siguiente register es limpio. |
| Token registration | Rotación concurrente/perdida | Token se persiste antes del worker; WorkManager REPLACE y mutaciones serializadas. |
| Revoke capability | Borrar credenciales antes de revocar | Limpiar solo tras éxito, `device_not_found` o `device_revoked`; `device_auth_failed` conserva credenciales y marca ERROR. Gap residual: credenciales inválidas pueden requerir intervención. |
| Event fetch | Fetch sin secret o con ID de otro device | Cliente V1 requiere `device_id` + `device_secret`; ausencia es `NO_DEVICE`; servidor valida ownership y devuelve `event_not_found`. |
| ACK | ACK antes de procesar contenido | Solo después de `DeliveryOutcome.SUCCESS`; ACK idempotente y dedupe persistente evita loops. |
| FCM payload | Inyección de contenido o versión desconocida | Mensaje opaque data-only; parser acepta solo UUID y versión ausente/`"1"`; descarta el resto. |
| Protocol downgrade | Servidor no compatible o respuesta v2 | `unsupported_protocol` es PERMANENT; capability probe selecciona LEGACY ante ruta ausente/503; mismatch no provoca crash. |
| Capability probe | Probe mutante que crea devices | Solo `events.pending` con device ID/secret dummy; nunca `device.register`. |
| Backup/restauración | Restaurar app en dispositivo/perfil incorrecto | SecureStore vacío fuerza re-register limpio; Keystore y almacenamiento son por usuario Android. |
| Cross-profile | Reutilización de device entre perfiles | SecureStore es por perfil; cada perfil mantiene su propio device registrado. |
| FCM transport | Firebase server credential en Android | FCM es BYO/opaque; no se incluye credencial server-side en Android ni `google-services.json` obligatorio. |
| Analytics | Terceros reciben IDs, contenido o tokens | No se integra third-party analytics; se mantienen los invariantes existentes. |

## 2. Residual and operational risks

El backend puede conservar un device huérfano si el proceso muere entre el register y la persistencia local; la idempotencia con credenciales evita duplicados cuando las credenciales sí se recuperan, y el caso sin credenciales se resuelve con un nuevo registro. Un `device_auth_failed` durante revoke no puede probar control del device: no se borran credenciales ni se reintenta infinitamente.

El downgrade es fail-safe: el probe puede elegir LEGACY ante ausencia del plugin o un fallo de red. La selección no convierte jamás un error de capacidad en un `device.register`. Protocol v2 futuro queda aislado por la validación estricta del envelope y por el descarte de versiones FCM desconocidas.

## 3. Security invariants

No se usa `HttpLoggingInterceptor`; mensajes de excepción contienen como mucho status y código, nunca cuerpos con credenciales. `allowBackup=false`, la política release de cleartext y la compilación sin `google-services.json` siguen vigentes. El dispositivo solo ACKea tras entregar la notificación, y las operaciones mutantes register/update/revoke/migración tienen una única sección crítica no reentrante por dispositivo lógico.
