# Hermes Assistant — a private Android client for Hermes

Hermes Assistant is an Android app that turns your phone into a voice + chat
client for a self-hosted [Hermes](https://hermes-agent.nousresearch.com) agent.
It **replaces Gemini as your device's digital assistant** (long-press the power
button, speak or type) and gives you a streaming voice conversation plus a
normal chat — all powered by *your* Hermes instance (its memory,
personalities, tools, and model). No wake word, no always-on microphone.

The app talks to **one thing only: your Hermes `api_server`** (the
OpenAI-compatible `/v1/chat/completions` endpoint). No companion server, no
sidecar — point it at your Hermes URL + API key and go.

## Fork notes

This repository is a private fork of
[Bwarhness/jarvis-assistant](https://github.com/Bwarhness/jarvis-assistant),
hardened for personal use with a self-hosted Hermes agent: no wake word, no
third-party voice providers, Keystore-protected credentials, and backups
disabled. Upstream history is preserved.

Remotes:

- `upstream` — the original Jarvis repository.
- `origin` — currently also points at upstream until a private fork remote
  exists; update it with `git remote set-url origin <private-url>` (do not
  invent a URL).

Sync with upstream:

```bash
git fetch upstream
git rebase upstream/master
```

Preserves Apache-2.0 licensing and upstream attribution (see Credits).

## Features

- 💬 **Streaming chat** with your Hermes agent (continuous sessions via `X-Hermes-Session-Id`).
- 🎙️ **Voice conversation mode** — speak, Hermes thinks and replies aloud, then listens again.
- 🤖 **Default digital assistant** — launch with the long-press / assist gesture, replacing Gemini.
- 🔊 Android's built-in offline TTS and on-device speech recognition where available.
- 🔒 Private by design: the Hermes key is Keystore-encrypted on-device, backups are
  disabled, and no third-party voice/cloud providers are involved.
- 🔔 **Durable event notifications (v0.5.0)** — Hermes stores device events and
  wakes the app through UnifiedPush/self-hosted ntfy; Android fetches content
  over the authenticated Hermes API and posts a native notification. Push sees
  only an opaque event ID, never reminder or conversation text. See the
  [event-notification architecture](docs/hermes-v0.5.0-event-notifications.md).

The **brain is always Hermes** — the app only handles the ears, mouth, face, and OS integration.

## Install

A prebuilt debug APK is in [`dist/`](dist/). With the phone connected and USB
debugging on:

```bash
adb install -r dist/Jarvis-0.1.0-debug.apk
```

Or copy the APK to the phone and tap it (allow "install from unknown sources").

## First-run setup

1. **Open Hermes Assistant → ⚙ Settings.**
2. **Base URL** — your Hermes `api_server`, e.g. `http://100.x.x.x:8642` (Tailscale) or `http://<lan-ip>:8642`.
3. **API key** — your Hermes `API_SERVER_KEY` (in Hermes' `.env`). Tap **Save & test connection** — you should see your models.
4. **Set as default assistant** (optional) — opens system settings; pick Hermes Assistant so the assist gesture launches it.

Tap the 🎤 in the chat top bar (or use the assist gesture) to enter voice
conversation.

## Build from source

Requires JDK 17 and the Android SDK (platform 34, build-tools 34). Set
`local.properties` with `sdk.dir=...`, then:

```bash
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

### Signed release

The shipped APK is debug-signed (fine for sideloading). For a release build, add
a `signingConfig` with your keystore and run `./gradlew :app:assembleRelease`.

## Architecture

| Layer | What |
|---|---|
| `hermes/HermesClient` | OkHttp SSE streaming to `/v1/chat/completions`; the only Hermes coupling. |
| `data/SecureStore` | Keystore-encrypted storage for the Hermes bearer token. |
| `voice/SpeechInput` | STT via Android `SpeechRecognizer` (on-device where available). |
| `voice/TtsEngine` | Android's built-in offline TTS. |
| `ui/ConversationViewModel` | The listen → think → speak → listen loop. |
| `assist/*` | `VoiceInteractionService` so the app can be the default assistant. |

Stack: Kotlin + Jetpack Compose, minSdk 29 / target 34.

## Caveats

- **On-device STT** quality/language support depends on the phone (API 31+
  uses the on-device recognizer when available; otherwise recognition may use
  the system/network provider).
- Release builds deny cleartext HTTP — point them at an HTTPS endpoint or a
  private route; debug builds allow plain LAN HTTP for development.

## Credits & license

Licensed under [Apache-2.0](LICENSE). Forked from
[Bwarhness/jarvis-assistant](https://github.com/Bwarhness/jarvis-assistant),
which this repository history preserves with thanks.
