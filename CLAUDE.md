# CLAUDE.md

Guidance for AI agents working in this repo. For the human-facing overview see
[README.md](README.md).

## What this is

**Hermes Assistant** — an Android client (Kotlin + Jetpack Compose) for a
self-hosted **Hermes** agent (`NousResearch/hermes-agent`). Forked from
Bwarhness/jarvis-assistant. It can replace Gemini as the device assistant:
**long-press power/assistant → speak or type → Hermes answers** via streaming
+ Android TTS.

There is **no wake word**, no always-on microphone, no third-party voice
providers. The brain is always Hermes; this app only does ears (STT), mouth
(TTS), face (Compose UI), and OS integration (assist role). The Hermes coupling
lives in `hermes/HermesClient.kt` + `hermes/Models.kt` (chat) and, when FCM is
configured, in `hermes/EventClient.kt` (device registration, event fetch/ACK).

Package: `dk.foss.jarvis`. Single Gradle module `:app`. No nav library, no DI
framework, no companion server.

## Build & verify

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest    # JVM unit tests
./gradlew :app:lintDebug            # 0 errors expected; warnings are tracked
```

JDK 17 + Android SDK platform 34 / build-tools 34. `local.properties` needs
`sdk.dir=...`. Capture Gradle's own exit code (`echo "EXIT=$?"` after the
command reports the echo's status, not Gradle's — use `$?` immediately or check
for `BUILD SUCCESSFUL`). A failed build can look green if you pipe through
other commands.

Verification = clean compile + tests + the running app on a device.

## Toolchain & versions (keep aligned)

- AGP **8.2.2**, Kotlin **1.9.22** (kotlin.android + serialization plugins move
  in lockstep, pinned in root `build.gradle`).
- Compose BOM 2024.02.02 (add Compose artifacts without versions); Compose
  compiler `1.5.10` is coupled to the Kotlin version.
- compileSdk/targetSdk **34**, minSdk **29**, Java 17 everywhere.
- Repositories only in `settings.gradle` (`FAIL_ON_PROJECT_REPOS`).
- Debug builds get `applicationIdSuffix '.debug'`.
- Release has `minifyEnabled false`; enabling R8 later needs keep rules for
  kotlinx-serialization.
- FCM feature: compiles without project credentials when no
  `google-services.json` matching the app's applicationId is present.

## Architecture — the listen → think → speak loop

| Layer | File(s) | Role |
|---|---|---|
| Wire protocol | `hermes/HermesClient.kt`, `hermes/Models.kt` | Hermes chat coupling: OkHttp SSE → `/v1/chat/completions`; `/v1/models` as connection test. When FCM is configured, `hermes/EventClient.kt` adds device-registration and event REST endpoints. |
| Shared HTTP | `net/Http.kt` | `Http.base` (bounded timeouts) + `Http.streaming` (`readTimeout(0)`). Reuse these; never build a new OkHttpClient. |
| Secrets | `data/SecureStore.kt` | AES-256-GCM key held in `AndroidKeyStore`; encrypted blob in app-private
  SharedPreferences. Interfaces (`AeadCipher`, `SecretBlobStore`) are injectable for JVM tests. |
| Settings | `data/SettingsStore.kt` | DataStore prefs: base URL (+ legacy-key migration into SecureStore). Model selection deliberately NOT stored — Hermes owns it. |
| Persistence | `data/ConversationStore.kt`, `data/ConversationRepository.kt` | One JSON file per conversation under `filesDir/conversations/`; repo is the single mutation point. |
| STT | `voice/VoiceRecognizer.kt`, `voice/SpeechInput.kt` | Android SpeechRecognizer; prefers `createOnDeviceSpeechRecognizer()` on API 31+ when available, else system recognizer (may use network provider). Single instance reused across turns. |
| TTS | `voice/TtsEngine.kt` | `AndroidTts` only. |
| Voice loop | `ui/ConversationViewModel.kt`, `ui/SentenceSplitter.kt` | ConvState Idle→Listening→Thinking→Speaking; recognition, streaming, sentence extraction, single-flight TTS pump, turn invalidation. |
| Assistant | `assist/JarvisInteractionService.kt`, `JarvisInteractionSessionService.kt`, `JarvisInteractionSession.kt`, `JarvisRecognitionService.kt` | Default-assistant role; long-press launches conversation mode. |
| UI / design | `MainActivity.kt`, `ui/*Screen.kt`, `ui/Theme.kt`, `ui/JarvisDesign.kt` | Compose screens + the "Direction A" design system. |
| Notifications | `push/FcmMessagingService.kt` | FCM `FirebaseMessagingService`; data-only wakes with `event_id` → WorkManager `FcmEventWorker`. |
| Events | `hermes/EventClient.kt` | Authenticated REST: register/update/revoke device, fetch/ack/pending events. |
| Delivery | `notifications/EventDelivery.kt`, `notifications/NotificationChannels.kt` | Dedup, fetch, post native notification, ACK (best-effort). |

**Hermes owns model selection.** `ChatRequest.model` is nullable and OMITTED
from the JSON body when unset (verified against hermes-agent v0.20.4:
`gateway/platforms/api_server.py` `_request_agent_overrides` → session `/model`
override → session-persisted model → gateway default). Do not reintroduce a
client-side default model. `X-Hermes-Session-Id` keeps server-side session
continuity; it is captured from the response header and persisted per
conversation.

## Conventions (match these)

- **Async correctness via a `turn` counter.** Capture `myTurn = turn` at the
  start of an operation; every recognizer/stream/TTS callback bails unless
  `turn == myTurn`. Bump `turn` in `beginTurn/resetView/stopAll/onMicTap`.
- **`hint` vs `error` are separate channels.** `hint` = soft/no-speech/transient
  mic hiccups on the Idle screen; `error` = hard Hermes stream failures shown by
  ErrorLayout. Never route no-speech into `error`.
- **`VoiceRecognizer.Listener.onError(message, transient)`**: transient=true ONLY
  for retryable hiccups; no-match/speech-timeout = false so the loop goes idle.
  The VM retries a transient error once per turn (450 ms).
- **TTS is single-flight.** `pump()` guards with `if (speaking) return`;
  advances from `speak`'s onDone/onError only.
- **Construct a fresh `HermesClient(baseUrl, apiKey)` per request**; never cache
  it. Callbacks: `onDelta` / `onSessionId` / `onComplete` / `onError`.
- **Wire/persisted types are `@Serializable` with defaults**
  (`HermesJson`: ignoreUnknownKeys, encodeDefaults, explicitNulls=false — nulls
  are omitted from request bodies). New persisted fields MUST default for
  on-disk compat.
- **Strip `isError` messages** before requests (`historyForRequest`) and before
  saving (`persist`).
- **`ConversationRepository`** uses an app-lifetime `ioScope` (not
  viewModelScope) so final saves survive `onCleared`; `sessionId`/dirty are
  `@Volatile` (written from SSE callback threads).
- **Edge-to-edge:** transparent bars in MainActivity; each screen applies its
  own insets (`statusBarsPadding()`, `imePadding()`, Scaffold padding).
- **Styling pulls from `JarvisColors` tokens + the 3 font families.** Reusable
  animated composables live in `JarvisDesign.kt`.
- **FCM lifecycle** is managed under an in-process non-reentrant `Mutex`
  (`FcmLifecycle.withLock`). Every operation re-reads state inside the lock
  before acting, self-healing across process death. Revoke is a persistent
  WorkManager worker with exponential back-off (5 local retries, then long-lived).
  Retry for push fetch: `MAX_RETRIES = 2` with WorkManager auto-retry.

## Security invariants (do not regress)

- The Hermes API key lives ONLY in `SecureStore` (AES-256-GCM key in
  AndroidKeyStore). Never in DataStore plaintext, never in BuildConfig, never
  logged, never echoed back into the Settings field (write-only input; blank
  save = keep existing).
- `android:allowBackup="false"` — keys and transcripts never leave the device.
- Release builds deny cleartext HTTP (platform default); debug-only manifest
  override (`app/src/debug/AndroidManifest.xml`) allows LAN HTTP for dev.
- No third-party network services beyond the user-configured Hermes URL and
  optional Firebase (FCM) for notifications.
- Never commit `keys.properties`, keystores, or any secret file (gitignored).
- FCM push messages contain **only an opaque `event_id`**; content is fetched
  from the authenticated Hermes API.

## Gotchas

- **`JarvisRecognitionService` is a deliberate no-op** (returns ERROR_CLIENT).
  A VoiceInteractionService must declare a recognitionService; deleting it
  breaks assistant registration.
- **The assist session must use `startAssistantActivity()`** — plain
  `context.startActivity` gets suppressed by background-activity-launch limits.
  Only the voice-interaction flow may display over the keyguard.
- **Reuse one `SpeechRecognizer` instance** across turns; create/destroy churn
  triggers `ERROR_SERVER_DISCONNECTED` (code 11).
- **`SpeechInput` privacy:** on-device recognizer when available (API 31+);
  otherwise the system recognizer may send audio to the platform provider's
  network service. Don't claim guaranteed-local transcription.
- **`extractSentences`/`SentenceSplitter`** splits only on `.`/`!`/`?` followed
  by whitespace (so `3.5` and trailing mid-stream `.` don't split) plus a soft
  cap at 180 chars. Covered by unit tests — keep them passing.
- **`SettingsStore` migrates legacy state on read:** old plaintext `api_key`
  moves into SecureStore and is purged; removed-feature keys (model, wake,
  eleven*) are purged too. Tests pin this behavior.
- **Notification taps** open `MainActivity` (ordinary chat UI) with
  `event_id`/`session_id` extras. They do **not** auto-start voice recording.
  The assist gesture is the only entry point for voice; notification extras
  are not trust signals.

## Config & secrets

There are NO build-time secrets. Base URL and API key are entered in Settings
at runtime. `local.properties` holds only `sdk.dir`.