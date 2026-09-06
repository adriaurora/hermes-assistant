# Hermes Assistant

Hermes Assistant is an Android app that turns your phone into a voice + chat
client for a self-hosted [Hermes](https://github.com/NousResearch/hermes-agent) agent.
It replaces the device's default digital assistant (long-press the power
button) and gives you streaming voice conversations plus normal chat — all
powered by *your* Hermes instance. No wake word, no always-on microphone.

The app talks to **one thing only: your Hermes `api_server`** (the OpenAI-compatible
`/v1/chat/completions` endpoint). When FCM is configured it also calls the
Hermes device-event REST APIs (`/api/devices/*`, `/api/events/*`). No companion
server, no sidecar — point it at your Hermes URL + API key and go.

## Features

- 💬 **Streaming chat** with your Hermes agent (sessions via `X-Hermes-Session-Id`).
- 🎙️ **Voice conversation mode** — speak, Hermes thinks and replies aloud, then listens again.
- 🤖 **Default digital assistant** — launch with the long-press / assist gesture.
- 🔊 Android's built-in offline TTS and on-device speech recognition where available.
- 🔒 **Private by design**: credentials are encrypted with AndroidKeyStore AES-256-GCM
  and backups are disabled. FCM is the optional third-party wake transport; no
  third-party voice or AI/cloud provider receives conversation content.
- 🔔 **FCM event notifications** — Hermes sends a data-only wake with an opaque `event_id`;
  the app fetches content over the authenticated Hermes API and posts a native
  notification. Push sees only an opaque event ID, never reminder or conversation text.
  See [docs/hermes-v0.5.0-event-notifications.md](docs/hermes-v0.5.0-event-notifications.md).

The **brain is always Hermes** — the app only handles the ears, mouth, face, and
OS integration.

## Install

Build from source (see below), or sideload a debug APK:

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## First-run setup

1. **Open Hermes Assistant → Settings.**
2. **Base URL** — your Hermes `api_server`, e.g. `http://<lan-ip>:8642`.
3. **API key** — your Hermes API key. Tap **Save & test connection** — you
   should see your models.
4. **Set as default assistant** (optional) — opens system settings; pick Hermes
   Assistant so the assist gesture launches it.

Tap the 🎤 in the chat top bar (or use the assist gesture) to enter voice
conversation.

## Build from source

Requires JDK 17 and the Android SDK (platform 34, build-tools 34). Set
`local.properties` with `sdk.dir=...`, then:

```bash
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

### FCM support

Notifications require Firebase. A variant-specific `google-services.json` must exist
under `app/src/debug/` or `app/src/release/`, with a client matching the
applicationId `io.github.adriaurora.hermesassistant` (these local configs are not
committed). When a matching config is present the FCM capability
is enabled and visible in Settings; without it the app runs in chat-only mode.
Release uses `io.github.adriaurora.hermesassistant`; debug adds the `.debug`
suffix (`io.github.adriaurora.hermesassistant.debug`). Each variant JSON must
contain its corresponding client.
The supported FCM layout is to provide both files (or a root
`app/google-services.json` containing both clients) when building both variants;
do not provide only one variant file, because the Google Services plugin can then
fail the other variant at configuration time. For chat-only builds, remove all
three possible config locations so the plugin is not applied.
Hermes must expose the device-registration and event REST endpoints
(`/api/devices/*`, `/api/events/*`) for notifications to work.
`Clear saved key` in Settings also revokes the device registration on your Hermes instance and disables event notifications until push is re-enabled; the revoke retries in the background and treats a 404 as already-revoked.

### Signed release

Debug builds are fine for sideloading. For a release build, add a
`signingConfig` with your keystore and run `./gradlew :app:assembleRelease`.

## Architecture

| Layer | What |
|---|---|
| `hermes/HermesClient` | OkHttp SSE streaming to `/v1/chat/completions`. |
| `data/SecureStore` | AES-256-GCM key held in AndroidKeyStore; encrypted blob in app-private
  SharedPreferences. |
| `voice/SpeechInput` | STT via Android `SpeechRecognizer` (on-device where available). |
| `voice/TtsEngine` | Android's built-in offline TTS. |
| `ui/ConversationViewModel` | The listen → think → speak → listen loop. |
| `assist/*` | `VoiceInteractionService` so the app can be the default assistant. |
| `push/FcmMessagingService` | FCM data-only wake → WorkManager worker (event ID only in push). |
| `hermes/EventClient` | Authenticated REST: device registration, event fetch/ACK, pending sync. |

`HermesClient` and `EventClient` are both deliberately coupled to the Hermes API;
there is no separate companion or proxy service.

Stack: Kotlin + Jetpack Compose, minSdk 29 / target 34.

## Caveats

- **On-device STT** quality/language support depends on the phone (API 31+
  uses the on-device recognizer when available).
- Release builds deny cleartext HTTP — point them at an HTTPS endpoint or a
  private route; debug builds allow plain LAN HTTP for development.
- FCM notifications require a Firebase config matching the app's applicationId and
  the Hermes backend must expose the device-event REST endpoints.
- Tapping a notification opens the ordinary main screen; `MainActivity` deliberately
  ignores notification extras and does not deep-link to or auto-start a conversation.

## Credits & license

Licensed under [Apache-2.0](LICENSE). Forked from
[Bwarhness/jarvis-assistant](https://github.com/Bwarhness/jarvis-assistant),
which this repository history preserves with thanks.
