# Hermes Assistant

Hermes Assistant is an Android app that turns your phone into a voice + chat
client for a self-hosted [Hermes](https://github.com/NousResearch/hermes-agent) agent.
It replaces the device's default digital assistant (long-press the power
button) and gives you streaming voice conversations plus normal chat — all
powered by *your* Hermes instance. No wake word, no always-on microphone.

The app sends chat requests to **your Hermes `api_server`**. Chat and voice use
the Sessions API; each conversation is backed by a server-side Hermes session.
When push is enabled, the optional `hermes_assistant` plugin provides the Wire
Protocol event endpoint. No companion server, no sidecar — point it at your
Hermes URL + API key and go.

## Features

- 💬 **Streaming chat** with your Hermes agent (sessions via `X-Hermes-Session-Id`).
- 🎙️ **Voice conversation mode** — speak, Hermes thinks and replies aloud, then listens again.
- 🤖 **Default digital assistant** — launch with the long-press / assist gesture.
- 🔊 Android speech recognition and text-to-speech, using on-device recognition where available.
- 🔒 **Private by design**: credentials are encrypted with AndroidKeyStore AES-256-GCM
  and backups are disabled. FCM is the optional third-party wake transport; no
  app-managed voice backend is used. The system speech provider may process audio
  or speech text online; Hermes may also use the AI providers configured on your server.
- 🔔 **FCM event notifications** — Hermes sends a data-only wake with an opaque `event_id`;
  the app fetches content over the authenticated Hermes API and posts a native
  notification. Push sees only an opaque event ID, never reminder or conversation text.
  Uses the optional [Wire Protocol v1](docs/wire-protocol-v1/) `hermes_assistant`
  plugin (RPC endpoint `POST /api/platforms/hermes_assistant/events`). See
  [FCM setup](docs/fcm-setup.md) for installation and verification.

The **brain is always Hermes** — the app only handles the ears, mouth, face, and
OS integration.

## Insecure HTTP policy

The app enforces a **fail-closed** network gate:

- **HTTPS is always allowed** — TLS verification is preserved. Self-signed or
  untrusted HTTPS certificates are **not** automatically bypassed.
- **HTTP is blocked by default** — no socket is opened to an unapproved endpoint.
- **HTTP opt-in** — when you enter an `http://` base URL in Settings the app
  shows a **blocking confirmation dialog** (not a banner or checkbox). The dialog
  states:

  > *HTTPS is strongly recommended. HTTP provides no confidentiality or integrity;
  > messages, voice transcriptions, and other data exchanged with Hermes could be
  > intercepted or modified by any actor on the network. Use only under your own
  > responsibility on networks you fully control.*

  The user must explicitly acknowledge this warning to permit HTTP for that
  specific endpoint (scheme + lowercased host + port + path). Approval is
  **per origin**, not per API key.

- **Automatic revocation** — when settings change and the old endpoint must be
  revoked, the HTTP approval for the old origin is removed after successful
  revoke cleanup.  The old endpoint remains reachable only for the revoke
  operation itself.

### Platform cleartext and application gate

The Android platform is configured to **allow cleartext** at the network
security-config level (`cleartextTrafficPermitted="true"`), so the platform
does not block HTTP traffic.  The **application gate** (`net/NetworkGate`) is
the strict policy boundary: HTTPS always allowed; HTTP only for explicitly
approved origins.  This separation means the platform permission is broad
(permissive) and the application enforces a narrow, user-consented policy.

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
   should see your models.  For HTTP URLs the app requires explicit consent
   via a blocking dialog (see *Insecure HTTP policy* above).
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
The optional `hermes_assistant` plugin must be installed and enabled on Hermes
for notifications to work. `Clear saved key` in Settings also revokes the
plugin device registration on your Hermes instance and disables event
notifications until push is re-enabled. See [FCM setup](docs/fcm-setup.md).

### Signed release

Debug builds are fine for sideloading. For a release build, add a
`signingConfig` with your keystore and run `./gradlew :app:assembleRelease`.

## Architecture

### Chat and voice sessions

Every new chat or voice conversation uses the capability-gated Sessions API.
The app creates a Hermes session lazily, binds the local conversation to the
server session ID, sends only each new turn, and resumes that session after a
restart. Voice is the same Sessions API conversation with Android STT/TTS; it
does not use a separate voice backend or chat transport. A server that does not
advertise Sessions chat is surfaced as unsupported rather than silently
switching transports. The former [Sessions API chat design](docs/sessions-api-chat.md)
is archival documentation, not the current operational contract.

### Push architecture (FCM)

```
Android (Sessions)                    Hermes server                  FCM push
─────────────────                     ──────────────                 ────────
• user chat / voice                    • cron / proactive events      • data-only wake
  → Sessions API                         → plugin RPC (v1)            → opaque event_id only
  → SSE streaming                        • http_event_auth_mode:      • Android receives wake
  → model lock                           api_server_key              • event fetch over
• FCM wake (opaque id only)                                                    Hermes authenticated API
  → WorkManager revoke worker                                              • notification post
  → EventRpcClient.fetchEvent                                                    • EventRpcClient.ack
  → NotificationManager
```

- **Chat / voice**: Android → Hermes Sessions API.  No plugin required.
- **Push (optional)**: Hermes cron/proactive → plugin (Wire v1) → FCM HTTP v1
  → Android → authenticated event fetch → notification → ACK.  Plugin **is
  required** for push.  The auth seam is `http_event_auth_mode="api_server_key"`
  on the plugin endpoint only — it does not affect Sessions authentication.

### Layer reference

| Layer | What |
|---|---|
| `net/NetworkGate` | Centralised fail-closed gate: HTTPS always allowed, HTTP only for explicitly approved origins.  Strict validation rejects malformed, opaque, userinfo, query, and fragment URIs for both HTTP and HTTPS before canonicalisation. |
| `net/Http` | Shared OkHttp clients (`base` + `streaming`), lazily initialised network gate from `HermesApplication`. |
| `net/ApprovedOriginsStore` | Persistent approved-HTTP-origins store (same DataStore + key as `SettingsStore`: `jarvis_settings` / `approved_http_origins`). |
| `hermes/HermesClient` | Sessions API (`/api/sessions/*`) for chat and voice, with SSE streaming when advertised. Gate validates before every call. |
| `data/SecureStore` | AES-256-GCM key held in AndroidKeyStore; encrypted blob in app-private SharedPreferences. |
| `data/SettingsStore` | Base URL, API key, and approved HTTP origins (single DataStore source of truth). |
| `voice/SpeechInput` | STT via Android `SpeechRecognizer` (on-device where available). |
| `voice/TtsEngine` | Android TTS; offline availability depends on the selected engine and voice. |
| `ui/ConversationViewModel` | The listen → think → speak → listen loop. |
| `ui/SettingsScreen` | Connection settings, **blocking** HTTP consent dialog with exact Spanish warnings, approved origins list with "Dejar de permitir HTTP para este servidor" revoke. |
| `assist/*` | `VoiceInteractionService` so the app can be the default assistant. |
| `push/FcmMessagingService` | Optional FCM data-only wake → WorkManager worker (event ID only in push). |
| `push/FcmRevokeWorker` | WorkManager worker: revokes the old device registration, clears HTTP approval after success. |
| `push/FcmRevokeCleanup` | Removes old HTTP origin approval after successful revoke.  If the old origin was manually revoked the worker treats the `BlockedRequest` as success (no infinite retry). |
| `hermes/EventRpc` | Optional plugin Wire Protocol v1: device registration, event fetch/ACK, and pending sync. Gate validates before every call. |

`HermesClient` and `EventRpc` are both deliberately coupled to the Hermes API;
there is no separate companion or proxy service.

Stack: Kotlin + Jetpack Compose, minSdk 29 / target 34.

## Caveats

- **On-device STT** quality/language support depends on the phone (API 31+
  uses the on-device recognizer when available).
- The platform allows cleartext by default; the application gate is the
  policy boundary.  All HTTP traffic requires explicit user consent via
  the blocking dialog in Settings.
- FCM notifications require matching Firebase config and the optional
  `hermes_assistant` plugin; see [FCM setup](docs/fcm-setup.md).
- A valid notification tap opens the matching Hermes session in text chat via a
  private, one-shot token. Arbitrary launcher extras are ignored. Notification taps
  do not start the microphone.
- Self-signed or otherwise untrusted HTTPS certificates are **not** automatically
  accepted — the platform TLS stack rejects them.

## Credits & license

Licensed under [Apache-2.0](LICENSE). Forked from
[Bwarhness/jarvis-assistant](https://github.com/Bwarhness/jarvis-assistant),
which this repository history preserves with thanks.