# Hermes Android Parity Audit

**Date:** 2026-08-22 (overnight audit)
**Scope:** Android client (`~/Dev/hermes-assistant`) vs real Hermes deployment on `ubuntu-services`.
**Method:** read-only inspection of both environments; seven bounded research passes (Android architecture, Telegram adapter, `/model`, api_server, Bufanatic, cron delivery, memory/session-search), each spot-verified by the lead auditor directly over SSH. No code, config, or service changes were made anywhere.

---

## 1. Executive summary

1. **The parity gap is mostly an Android gap, not a Hermes gap.** The installed api_server already exposes far more than the app uses: full session CRUD + history + fork (`/api/sessions*`), model discovery and per-session model lock (`GET /api/model/options`, `POST /api/sessions/{id}/model`), jobs CRUD (`/api/jobs*`), a complete run lifecycle with **approval/steer/stop** (`/v1/runs…`), and capability discovery (`/v1/toolsets`, `/v1/capabilities`). The Android client speaks exactly two endpoints.
2. **Model control needs zero new server machinery.** Telegram's `/model` is ~95% Hermes-core (`hermes_cli/model_switch.py` + gateway/session stores). Android gets persistent switching via the session-model APIs and one-shot switching via the request-body `model` field (subject to `model_routes`/`allow_bare_model` config gating). Only the inline-keyboard picker is Telegram UX — do not copy it.
3. **Bufanatic is transport-independent and already safe by construction.** Its two-step "execution" confirmation performs **zero external mutations by design** ("Execution confirmed (not executed)"); the only real mutations are `approve`/`reject`, gated by session-provenance checks and exact-UUID arguments. Enabling it on api_server is a one-line config change that exposes all 9 tools — a read-only split is recommended first.
4. **Memory and history are already global.** One `MEMORY.md`/`USER.md`, one `state.db` (live counts: cli 11, telegram 8, api_server 5, cron 4 sessions in the same table), FTS5 search across all sources. Android conversations are already searchable from Telegram today. The local JSON transcript store must remain a UI mirror, never a second memory.
5. **Two genuine server-side gaps exist:** (a) no file-upload endpoint (why `file` stays Telegram-only), and (b) api_server is **not a delivery lane** — reminders created from Android default to silent storage (`deliver="local"`) because `_send_to_platform` has no api_server branch. Everything else can be closed client-side or with small generic Hermes additions.

**Recommended order:** A) conversational/session parity (pure Android) → B) model control (small Android + config) → C) Bufanatic read (tiny plugin split + config) → E) reminder polling (Android-only interim) → D) files (new generic endpoint) → F) controlled mutations via the runs approval flow → G) broader capabilities.

---

## 2. Verified environment / topology

| | Environment A (Mac) | Environment B (ubuntu-services) |
|---|---|---|
| Access | local | `ssh adrian@192.168.88.31` + passwordless sudo (no `ubuntu-services` alias exists in `~/.ssh/config`; hostname confirms identity) |
| Path | `/Users/adrianlaborda/Dev/hermes-assistant` | `/home/hermes/.hermes/hermes-agent` (owned by user `hermes`) |
| Branch | `hermes-assistant-mvp` | `hermes-bufanatic-integration` |
| HEAD | `019cdbb11aed17e64ee1cd9ed0850aee2748d8ad` | `fa3e877954ea720b0a4e73c8623e2840d066a195` = **v2026.8.18-6-gfa3e877954** |
| Tree | clean | clean (verified twice; one subagent's "uncommitted Bufanatic deltas" claim was refuted — `tools.py:661` identical in HEAD and worktree) |
| Service | — | `hermes-gateway.service`: **active**, enabled |
| Config | — | `/home/hermes/.hermes/config.yaml` (+ `.env`); secrets redacted throughout this audit |

Live toolset config (verified at `config.yaml:35-49`):

```yaml
platform_toolsets:
  api_server: [web, todo, memory, clarify, cronjob, session_search]
  telegram:   [clarify, cronjob, session_search, memory, todo, web, file, bufanatic]
  cron:       [bfl, clarify, cronjob, …]
```

Upstream remotes on the install: `origin → github.com/NousResearch/hermes-agent`. Upstream currently has one newer tag: `v2026.8.19` (= v0.20.5). See §16.

**Confirmed topology statement:** *Android source was inspected locally on the Mac; Hermes server and Telegram integration were inspected remotely on ubuntu-services. No clone of Hermes exists on the Mac.*

---

## 3. Android architecture (Environment A)

### Wire surface
- Exactly two endpoints: `POST /v1/chat/completions` (`HermesClient.kt:41`) and `GET /v1/models` (`HermesClient.kt:94`, used only as a connection test).
- SSE via OkHttp `EventSources`; `[DONE]` sentinel; `X-Hermes-Session-Id` captured from the response header (`HermesClient.kt:52-54`); **no retry logic anywhere**; cancellation is cooperative (`EventSource.cancel()` held by ViewModels).
- `ChatRequest.model` is nullable and omitted from the body when null (pinned by `ChatRequestJsonTest`). **No caller can set it** — the parameter is unreachable from any screen.
- Stream decoding extracts only `delta.content` (`Models.kt:31-41`, `HermesClient.kt:62-64`). Server progress frames (`event: hermes.tool.progress`) arrive but are invisible to the UI.

### Voice loop
- `ConversationViewModel`: state machine `Idle→Listening→Thinking→Speaking`, guarded by a turn counter invalidated in `beginTurn/resetView/stopAll/onMicTap`; single-flight TTS pump advancing only from utterance callbacks; `SentenceSplitter` chunks for speech (180-char soft cap); 350 ms idle-flush speaks buffered sentence when stream pauses; an 800 ms stall heuristic flips a generic "WORKING" label — a guess about server internals.
- Strict hint-vs-error channels; transient recognizer errors retried once (450 ms).
- STT prefers the on-device recognizer (API 31+), audio may never leave the phone — *better privacy than Telegram's server-side whisper*. TTS is device-local `AndroidTts`.

### State & persistence
- One JSON file per conversation under `filesDir/conversations/`; `sessionId` persisted per conversation and restored on reopen; error rows stripped from requests and disk; app-lifetime IO scope survives `onCleared`.
- **Dual context mechanism:** every request replays full client-held history **and** carries the session id. The client decides what the model "remembers" per turn (`historyForRequest`) — the one place where interface-tier logic owns a state decision.
- Secrets: API key only in SecureStore (Keystore AES-256-GCM); legacy plaintext migrated and purged.

### Assistant integration
- `VoiceInteractionService` trio; long-press → `startAssistantActivity()` (exempt from background-activity limits) → MainActivity detects assist intent → auto-starts listening; show-when-locked + `requestDismissKeyguard` once triggered; metadata declares `supportsLaunchVoiceAssistFromKeyguard=false`.

### Verdict against the principle "Android = interface, Hermes = intelligence"
Clean overall. Two violations / risks:
1. **Shadow archive:** local transcripts are invisible to every server capability (search, reflection, cross-platform recall). Deletion lifecycles diverge.
2. **Client-owned session lifecycle:** stale session ids are re-sent forever without validation; local delete never informs the server (server rows linger).

Missing capabilities that the protocol/server would allow today: session listing/history (`/api/sessions`), model options + selection, job management, run approvals, tool-progress rendering, attachments, proactive notifications. All extension points are identified in §17.

---

## 4. Real Hermes architecture (Environment B)

- **Gateway** process (`hermes-gateway.service`) hosts platform adapters (Telegram is a *plugin platform*: `plugins/platforms/telegram/adapter.py`, ≈10.9k lines) plus the aiohttp **api_server** (`gateway/platforms/api_server.py`, ≈7.6k lines).
- **One brain:** every platform constructs the same `AIAgent` with the same `SessionDB` (`state.db`: tables `sessions`, `messages`, FTS indexes, `gateway_routing`) and the same `MemoryStore` (`memories/MEMORY.md`, `USER.md`). Persona from `SOUL.md` as primary identity tier.
- **Tool resolution:** `platform_toolsets.<platform>` selects named toolsets per platform (`hermes_cli/tools_config.py:2403`); missing entry falls back to a per-platform composite default; `agent.disabled_toolsets` prunes globally (terminal, code_execution, browser, computer_use, delegation, kanban are disabled here).
- **Cron:** `cron/jobs.json` + `executions.db`; scheduler runs inside the gateway; delivery targets resolved at fire time (`cron/scheduler.py:2148-2248`).
- **Plugins:** dir plugins with `plugin.yaml` + `register(ctx)`; enabled: `bufanatic` only.

---

## 5. Telegram architecture (as deployed)

- Long-polling bot with hardened transport (watchdogs, IP failover); handlers for text/commands/location/media/callback queries/reaction+edit observer (`adapter.py:4203-4231`).
- **AuthZ before anything:** env allowlists (`TELEGRAM_ALLOWED_USERS`, `TELEGRAM_GROUP_ALLOWED_CHATS`) → pairing store → deny; slash commands additionally gated by an admin/user axis (`gateway/slash_access.py`); floor always allowed: `help`, `whoami`.
- **Voice in:** voice notes cached → server-side transcription (provider `openai` via nan.builders, model `whisper`, language `es`, echo off; local faster-whisper fallback). Documents/audio are never auto-transcribed.
- **Voice out:** gated by per-chat `/voice` mode + global `voice.auto_tts`; streaming sentence-chunked TTS consumer exists; **currently off** on this box (no `voice:` key).
- **Files:** photos/documents downloaded into `~/.hermes/cache/{images,media}`; path handed to the agent; text-like files ≤100 KB inlined; the native **`file` toolset reads them**; 20 MB cap; authorization checked before download.
- **Sessions:** deterministic key `agent:main:telegram:dm:<chat_id>` → session id in `state.db.gateway_routing`; interrupted sessions auto-resume within a freshness window (3600 s).
- **UX layer (correctly adapter-specific):** MarkdownV2 formatting, UTF-16-aware 4096-char splitting, reactions 👀→👍/👎, typing ticks, draft streaming, inline keyboards (model picker, clarify, approvals, confirmations).
- ~60 slash commands (registry `hermes_cli/commands.py:131`), including transcript ops, `/sethome`, approvals, memory review, skills, quick commands.

---

## 6. api_server architecture (as deployed)

Verified route table (`api_server.py:2061-2106`):

| Group | Endpoints |
|---|---|
| Health | `GET /health`, `/health/detailed`, `/v1/health` |
| Models | `GET /v1/models`, `GET /api/model/options` |
| Discovery | `GET /v1/capabilities`, `/v1/skills`, `/v1/toolsets` |
| Sessions | `GET/POST /api/sessions`, `GET/PATCH/DELETE /api/sessions/{id}`, `GET …/messages`, `POST …/fork`, `POST …/chat[/stream]`, `POST …/model` (lock) |
| Chat | `POST /v1/chat/completions` (SSE), `POST/GET/DELETE /v1/responses[/{id}]` |
| Runs | `POST /v1/runs` (202), `GET /{id}`, `GET /{id}/events` (structured SSE), `POST /{id}/approval|steer|stop` |
| Jobs/cron | `GET/POST/PATCH/DELETE /api/jobs[/{id}]`, pause/resume/run; `POST /api/cron/fire` (inbound NAS webhook, JWT) |

- **Auth:** `Authorization: Bearer $API_SERVER_KEY`, timing-safe compare; session continuation requires the key (else 403); `/health` is open.
- **Sessions:** `X-Hermes-Session-Id` sanitized ≤256 chars → history loaded from `state.db`; absent header ⇒ derived fingerprint id so OpenWebUI-style clients still coalesce; effective id echoed back in the response header (including compression rotation). No TTL/GC for API-created rows.
- **Model precedence (verified verbatim in this install):** confirmed Browser lock → session `/model` override → session-persisted model (`POST /api/sessions {"model":…}`) → `model_routes` alias selected by request `model` → direct per-request provider/model (gated by `direct_model_requests.allow_bare_model`) → global default. Shared resolver: `hermes_cli.model_switch.resolve_effective_model`. Request-scoped `model_options` (reasoning effort etc.) also accepted. Client-supplied OpenAI `tools`/`temperature` are ignored.
- **Streaming:** standard `chat.completion.chunk` content deltas **plus custom `event: hermes.tool.progress` frames** (`{tool, emoji?, label?, status: running|completed}`), 30 s keepalives, terminal `finish_reason: stop|length|error`; client disconnect interrupts the agent.
- **Clarify is inert** on chat endpoints: `clarify_callback` defaults to None ⇒ the clarify tool returns an error; the interactive answer path is wired only into gateway adapters. (Fixable generically; see §13.)
- **Approvals fully exist**: pending approval surfaces as an `approval.request` event on the runs stream; resolved via `POST /v1/runs/{run_id}/approval {"choice": once|session|always|deny}`.
- **Not a delivery lane:** `_send_to_platform` (`tools/send_message_tool.py:1267-1312`) covers whatsapp…yuanbao + plugin platforms — **no api_server branch**; `Platform.API_SERVER` is not in the known-delivery set (`cron/scheduler.py:458-464`).

**Verdict:** api_server is already generic and broad enough that *no Android-specific endpoints are needed* for near-full parity. Remaining generic gaps: file upload, clarify bridge, outbound delivery lane.

---

## 7. Parity matrix

Legend: ✅ works · 🟡 partial · ❌ absent · (A)=agent-mediated only. Evidence refs abbreviated; all verified this audit.

| Capability | Hermes core | Telegram | api_server | Android today | Security Δ | Work for parity | Recommended UX |
|---|---|---|---|---|---|---|---|
| Ordinary chat | ✅ | ✅ | ✅ `/v1/chat/completions` | ✅ voice+text | none | — | keep |
| Streaming | ✅ delta callbacks | ✅ edit-in-place drafts, typing | ✅ SSE + `hermes.tool.progress` + keepalive | 🟡 content only; generic "WORKING" guess | none | render progress chips (client) | "checking calendar…" style status |
| Session continuity | ✅ `state.db` | ✅ deterministic per-chat key | ✅ header ↔ row, echoed back | 🟡 persists id but also replays full history | low: replay may duplicate/conflict context | trust server history; keep replay as fallback | keep |
| New conversation | ✅ rotate + clear override | ✅ `/new` confirm-gated | ✅ omit header; `DELETE /api/sessions/{id}` exists | 🟡 clears id locally; server row lingers | minor orphan rows | optionally DELETE server row | "New" also deletes known server row |
| Session search | ✅ `session_search` FTS5 | ✅ enabled | ✅ enabled | 🟡 (A) ask verbally | none | none — already parity via agent | later: search UI over `/api/sessions` |
| Native memory read | ✅ global MEMORY.md | ✅ memory toolset | ✅ memory toolset | 🟡 (A) conversational | none | none | conversational |
| Native memory write | ✅ same store | ✅ | ✅ | 🟡 (A) conversational | write_approval gate off here; **no HTTP approval surface if enabled** | only if gate enabled (§12) | conversational |
| Web | ✅ web toolset | ✅ | ✅ | 🟡 (A) | none | none | — |
| Todo create/read/update | ✅ todo toolset | ✅ | ✅ | 🟡 (A) | none | none | — |
| Cron create/list/pause/delete | ✅ cronjob tool | ✅ | ✅ tool **and** REST `/api/jobs*` | 🟡 (A) create works; results silently stored | deliver=local ⇒ silent (§11) | NEXT E | conversational + reminders inbox |
| Model inspection | ✅ `/status`,`/model` | ✅ bare `/model` + picker | ✅ `GET /v1/models`, `GET /api/model/options` | ❌ fetches ids then discards | none | NEXT B | model chip + bottom sheet |
| Persistent model switch | ✅ session override store | ✅ `/model <alias>` persists | ✅ `POST /api/sessions/{id}/model` lock; session-persisted model | ❌ | alias names visible to user | NEXT B | per-conversation selector |
| One-shot switch | ✅ `--once` restore machinery | ✅ `/model x --once` | 🟡 request-body `model` (needs `model_routes` aliases or `allow_bare_model`) | ❌ param unreachable | gating config decides blast radius | NEXT B (optional phase) | defer; picker first |
| File upload/read | ✅ native file toolset | ✅ download→cache→path | ❌ **no upload endpoint** | ❌ | agent fs scope — why it's excluded | NEXT D (generic upload first) | share-sheet → attach |
| Image handling | ✅ vision enrichment | ✅ photos→cache→vision | 🟡 outbound data URLs only | ❌ | — | with NEXT D | photo attach |
| Voice STT | ✅ transcription tools | ✅ server-side whisper(nan) | n/a client concern | ✅ on-device recognizer | **Android strictly better privacy** | none | keep client-side |
| TTS | ✅ streaming consumer | ✅ `/voice` mode (off here) | ❌ no server TTS | ✅ device-local AndroidTts | none | none | keep device TTS |
| Bufanatic read ops | ✅ 5 READ tools | ✅ enabled | ❌ not in api_server toolsets | ❌ | read-only anyway | NEXT C | voice queries |
| Bufanatic approve | ✅ mutation tool | ✅ | ❌ | ❌ | MUTATION — deliberately excluded | NEXT F via runs approval | typed confirm restating action |
| Bufanatic reject | ✅ | ✅ | ❌ | ❌ | mutation-lite | NEXT F | same |
| Bufanatic execution | ✅ two-step confirm, **deliberate no-op** | ✅ proven live | ❌ | ❌ | execution impossible from Hermes by design | nothing to enable | explain-only answers |
| SEO planning/status | ✅ reads | ✅ | ❌ | ❌ | read-only | NEXT C | voice |
| Notifications | ✅ delivery + home channels | ✅ push via bot | ❌ not a lane | ❌ | — | NEXT E (poll→push) | local notifications |
| Clarification | ✅ clarify_gateway | ✅ inline keyboards | 🟡 tool present but inert (no callback) | ❌ | UX gap, not security | small generic Hermes bridge later | structured question card |
| Long-running operations | ✅ bg/steer/approvals | ✅ slash commands | ✅ `/v1/runs` lifecycle complete | ❌ unused | none | adopt runs API (NEXT A/F groundwork) | background + completion notification |

---

## 8. Model-control findings (Part 6)

**Where selection lives (all verified):**
1. Per-session override dict in the gateway runner (`_session_model_overrides`), rehydrated per turn; written by `/model <x>` (persist), cleared by `/new`, restored after one turn for `--once` (`run.py:17820,25997`).
2. Session-persisted model column in `state.db` (settable via `POST /api/sessions {"model":…}`).
3. `--global` writes `config.yaml`.
4. Aliases: config `model_aliases` — live values: `mimo`, `qwen`, `deepseek`; default model `mimo-v2.5` via the custom nan.builders provider.
5. Resolution shared by gateway and api_server: `resolve_effective_model` — **the server is the single routing authority; there is nothing to duplicate client-side.**

**What is genuinely Telegram-specific:** the inline keyboard picker (`send_model_picker`, `adapter.py:6350`), unicode-dash flag normalization, DM-topic key normalization, command-menu ordering. Nothing semantic.

**Can Android trigger equivalents today? Yes:**
- Persistent per-conversation switch: `POST /api/sessions/{session_id}/model` (verified route) — mirrors `/model`'s session binding.
- One-shot per request: send `"model"` in the chat-completions body. ⚠️ Gated: arbitrary strings require `direct_model_requests.allow_bare_model`; alias-shaped strings map through `model_routes`. Today neither is configured for api_server, so one-shot switching needs either those config keys or use of the session-lock API.
- Listing: `GET /api/model/options` / `GET /v1/models` (already fetched by the app's connection test — the data is discarded).

**Natural-language switching ("usa Qwen"):** the agent has **no tool** to change its own model (`_is_intentional_model_switch` is a detector only), and that is correct. NL switching therefore requires either (a) a tiny future generic Hermes addition — an agent-facing `set_session_model` tool bound to the existing session-store writer — or (b) client-side NLU guessing alias names, which violates the authority principle. **Recommendation: option C** — compact in-conversation picker now (server-authoritative via session APIs), NL switching later via the generic server tool, never via client parsing.

**Do NOT copy Telegram's `/model` textbox or picker.** Android-native shape: a small chip above the input showing the active model; tap → bottom sheet fed by `/api/model/options` grouped by alias/provider; selection binds to the current conversation's server session; voice answer to "¿qué modelo usas?" comes from the agent itself (it knows its runtime), not from client state.

---

## 9. Bufanatic findings (Part 7)

Plugin: `plugins/bufanatic/` — `kind: backend`, registers **one toolset `bufanatic`** with 9 tools + a read-only system-prompt guidance section. Transport-independent: handlers receive `session_id`/`user_task` from agent-core plumbing identical on api_server; proven working over plain text turns on Telegram (live log: "Ejecuta la segunda acción SEO" → step 1; next message "Sí" → confirmed).

| Tool | Class | External effect |
|---|---|---|
| `list_bufanatic_pending_actions` | READ | GET `/planning/pending`; attaches ordinals 1..N; remembers ID list per session |
| `get_bufanatic_action` | READ | GET `/actions/{uuid}` |
| `get_bufanatic_metrics` | READ | GET `/actions/metrics` |
| `get_bufanatic_outcomes` | READ | GET `/seo/outcomes` |
| `get_bufanatic_rollback_recommendations` | READ | recommendations only — performs no rollback |
| `request_bufanatic_execution` | READ* | records in-memory `pending` phase; **zero HTTP POST** |
| `confirm_bufanatic_execution` | READ* | flips phase to `confirmed`; **zero HTTP POST** — "Execution confirmed (not executed)" |
| `approve_bufanatic_action` | **MUTATION** | POST `/actions/{id}/approve` |
| `reject_bufanatic_action` | **MUTATION** | POST `/actions/{id}/reject` |

\* deliberate no-op: Hermes cannot execute website changes at all; rollback likewise impossible.

**Safety mechanics already present (do not rebuild):**
- Ordinal resolution: lists carry ordinals; system prompt orders the agent to resolve against the immediately preceding list, fetch detail first, and never guess IDs; tools accept **exact UUIDs only**, validated against session provenance (`ACTION_CONTEXT.contains(session_id,…)` → else "Action not owned by session") plus fresh GET + status gate before any mutation.
- Confirmation: step 1 requires an explicit execute verb in the user text; step 2 requires an unambiguous affirmative (`sí, confirmo, adelante, dale…`); negations fail closed. State is in-memory per gateway process — a restart between steps drops the pending request (**fail-closed**). No TTL Hermes-side (Bufanatic's own `approval_expires_at` exists but is unenforced by the plugin).
- Errors are sanitized fixed strings; tokens/headers/bodies never surfaced; every invocation logged to `logs/agent.log` with loop-guard after 3 failures. No `dry_run` flag exists (not needed given the no-op execution design).

**Path to Android:** the tools work identically once listed in `platform_toolsets.api_server`. Caveat: enabling today exposes **all 9 tools including mutations**. Recommended sequencing:
- **Stage A (read-only):** split the toolset in the plugin (`bufanatic_read` / `bufanatic_mutate`) — a small change confined to the owner's integration branch — enable `bufanatic_read` on api_server. Voice flows like "¿Qué acciones SEO tengo pendientes?", "¿Cuál es la segunda?", "Explícame por qué propone cambiarla", "¿Cómo están funcionando?" then work immediately through the normal agent loop; ordinal handling is already server-owned so Android never sees or invents IDs.
- **Stage B (mutations, later):** expose `bufanatic_mutate` only behind the existing runs-approval channel (§14) with the confirmation wording pattern already established ("Voy a aprobar la acción X para [resource], que cambia A por B. ¿Confirmas?"). Approval ≠ execution remains true by construction.
- Do not enable Bufanatic on api_server during/prior to that work (owner decision D2).

---

## 10. File / attachment findings (Part 8)

- Telegram pipeline: authorized download → `~/.hermes/cache/{images,media}` (shared cache helpers in `gateway/platforms/base.py`) → `event.media_urls=[path]` → text-like files ≤100 KB inlined as `[Content of name]:…`, binaries noted by path → native **`file` toolset** does reading/vision. Limits: 20 MB public Bot API. Authz precedes download.
- api_server: **no upload endpoint exists** (only outbound media-as-data-URL). Therefore `file` cannot simply be switched on for Android — and shouldn't be: enabling `file` alone would grant the agent filesystem-read reach with no way for the phone to provide documents.
- **Design (future, NEXT D):** one generic Hermes addition — `POST /v1/files` (multipart, bearer-authed) storing into the same cache layout and returning `{id, path, mime, size}`; clients reference it in the message (or the server injects the same `[Content of name]` note Telegram builds). Reuses existing cache/security helpers; no new trust boundary beyond "authenticated uploader may add files the agent can read".
- **Android UX:** system share-sheet target ("share → Hermes Assistant") and an attach button in ChatScreen; voice: "te he compartido un documento" flows through the same path. Out of scope until the endpoint exists.

---

## 11. Cron / reminder-delivery findings (Part 9)

- Storage: `cron/jobs.json` + `executions.db`; jobs carry `deliver` ∈ {`origin`, `local`, `all`, `platform:chat[:thread]`} and an `origin {platform, chat_id, thread_id}` snapshot captured at creation from session-context env vars. Default: **`deliver="origin"` if origin else `"local"`** (`cron/jobs.py:1873`).
- Fire-time resolution walks origin → home-channel fallbacks → explicit targets (`scheduler.py:2148-2248`); home channel = `TELEGRAM_HOME_CHANNEL` env (wins over any config block by design).
- **The asymmetry that matters:** jobs created via `POST /api/jobs` default to `deliver="local"` and get stamped `origin={platform:"api_server", chat_id:"api"}` — and since api_server has **no sender**, both paths end in silence. Results *are* persisted (`last_status`, `last_output`) and pullable via `GET /api/jobs/{id}`. `/api/cron/fire` is inbound-only (NAS Chronos webhook) and irrelevant for delivery.
- **Where should an Android-created reminder land?** Evaluation:
  - *Telegram delivery:* works today (config `deliver=telegram:<home>`), but reminders arrive in the wrong app; acceptable stopgap, not the goal.
  - *Poll-based Android notifications:* app creates the job, polls `GET /api/jobs?…`, posts a local notification when `last_run_at` advances past creation. Works **today** with zero Hermes changes; latency bounded by poll cadence; requires LAN/WireGuard reachability the app already assumes. ← recommended interim (NEXT E phase 1).
  - *True push:* make api_server a real delivery lane — a small authenticated event feed (e.g. `GET /api/events/stream` SSE or long-poll queue) fed by the same `_deliver_result` chokepoint when the target resolves to `api_server`; devices subscribe with the bearer token. This reuses existing target-resolution plumbing; only the send branch is missing. ← recommended end-state (NEXT E phase 2).
- The long-term abstraction is therefore: **delivery targets stay platform-shaped; api_server becomes a first-class target with a device-subscription semantic.** No new "notification system" should be invented beside it.

---

## 12. Memory & session-search findings (Part 10)

- **Global memory confirmed:** single `memories/MEMORY.md` + `USER.md`; every platform initializes the same `MemoryStore` (`agent/agent_init.py:1780-1787`); injected as a frozen snapshot at session start; mid-session writes hit disk immediately and appear next session. Tool surface: one `memory` tool (`add|replace|remove` × `memory|user`).
- **Shared transcript corpus confirmed live:** one `state.db`; session sources counted directly: `cli 11, telegram 8, api_server 5, cron 4` — same table, 424 messages. `session_search` (FTS5) filters by hidden-source exclusion only — **no platform filter**. An Android conversation with `X-Hermes-Session-Id` is server-persisted and recallable from Telegram, CLI, cron — today.
- **Visibility is intentionally asymmetric:** api_server can list/open everything (`source=` filter optional); gateway `/sessions`//resume refuse cross-origin bindings (IDOR guard, `slash_commands.py:1108-1160`). Correct; don't loosen.
- **Answer to the design question:** (A) Android uses **only server memory/search** — recommended. The local `ConversationStore` remains a UI mirror/cache; it must not gain memory semantics, sync logic, or its own recall. Optionally later, hydrate the History screen from `/api/sessions` so the phone reflects server truth (decision D1).
- Gap noted: `memory.write_approval` staging has **no HTTP review surface** (gateway-slash/CLI only). Irrelevant while the gate is off (it is, here); revisit only if the owner enables the gate.

---

## 13. Voice-native UX design (Part 11)

Principles: spoken channel = short, screen channel = rich; the phone owns ears/mouth/attention; the server owns meaning/state.

- **Concise spoken + detailed screen:** already structurally true (sentence-chunked TTS while full stream renders). Optional refinement: a voice-mode brevity directive sent as an ephemeral system overlay (protocol-supported, `api_server.py:4204-4216`) — presentation-level shaping, acceptable under the authority principle; keep persona edits out of the client.
- **Barge-in:** mic tap during `Speaking` should cancel SSE + stop TTS and reopen the mic — pure client work extending the existing turn-counter pattern (currently only Listening handles taps).
- **Cancel / follow-up:** stop button + continuous mode already exist; keep semantics identical across modes.
- **Real progress instead of "WORKING":** parse `hermes.tool.progress` frames and speak/show "comprobando…" — replaces the 800 ms heuristic guess.
- **Clarification:** until the generic clarify bridge exists on api_server, the agent already asks questions in plain text — sufficient. Later: structured question cards driven by a `clarify.request` event (mirrors the runs-approval event pattern).
- **Long-running tasks:** adopt `/v1/runs` (202 + events SSE + steer/stop) so a task can continue server-side while the screen locks; completion arrives as a local notification (NEXT E infrastructure).
- **Errors:** keep the strict hint/error split; map `finish_reason:error` and 401/403 distinctly (auth vs transient).
- **Lockscreen:** keep launch-from-keyguard disabled; assistant triggers self-promote as today. Sensitive actions require an unlocked interaction — the server cannot see device lock state, so this stays a client policy (see §14).
- **Comparison:** relative to Gemini/Siri/Alexa, Hermes-on-Android's differentiators are session/memory continuity and server-side tools; the missing piece they have is proactive notification delivery (NEXT E) and interruption polish (above). Wake-word behavior remains explicitly out of scope.

---

## 14. Security & capability architecture (Parts 5/8/12)

- **Keep the current platform-based policy.** `platform_toolsets.<platform>` + global `disabled_toolsets` is sufficient for the whole roadmap below. Multi-axis policies (per-user/device/mode/risk) are architecturally interesting but premature — no second principal exists on api_server (single bearer token = owner), and device-lock state is unknowable server-side. Revisit only if a second human or an untrusted device class appears.
- **Never available through voice alone (policy, enforceable via toolset choice + confirmation design):** purchases/payments; sending messages to third parties; deleting data; executing code/server administration; Bufanatic execute-class effects (currently impossible by construction — keep it that way); exposing credentials/tokens.
- **Tiered confirmation policy:**
  - Reads: free.
  - Low-stakes mutations (`approve`/`reject` SEO actions): explicit confirmation that restates resource + consequence — the plugin's existing wording pattern; on voice, accept only strong keywords (`confirmo`, `adelante`), **never bare "sí"** — Spanish ASR confounds `sí`/`si` (conditional "if") too easily.
  - Destructive/irreversible: require unlocked device + typed/explicit keyword confirmation; consider requiring these flows through the runs-approval channel so every decision is a persisted event (`approval.request/responded`) — free audit trail.
- **Audit trail exists:** `logs/agent.log` records every tool invocation; transcripts persist in `state.db`. Route high-stakes flows through mechanisms that log by design rather than adding new logging.
- **Transport:** LAN/WireGuard HTTP is today's tradeoff (debug cleartext only); TLS via reverse proxy is an owner hardening item (D6), not a blocker.

---

## 15. Telegram-specific technical debt (Part 13)

Inspected candidates — verdicts:

| Candidate | Current location | Telegram-specific? | Action |
|---|---|---|---|
| Model switching | core: `hermes_cli/model_switch.py`, gateway stores, session APIs; adapter: inline keyboard only | No (~95% core) | None — Android consumes session APIs |
| Attachment preprocessing | download in adapter (necessarily); caching helpers shared in `gateway/platforms/base.py` | Half | Future `POST /v1/files` should reuse the shared cache helpers; abstraction already exists |
| Response formatting | adapter MarkdownV2/UTF-16/drafts | Yes, correctly | None |
| Approval UX | core: `tools/approval.py` + runs events; adapter: buttons | Correctly split | None — Android uses events + POST |
| Home-channel routing | core: `gateway/delivery.py`, `cron/scheduler.py`, `config.get_home_channel` | No — core | Add api_server sender branch (NEXT E phase 2) |

**Conclusion: little true debt.** The gap is absent *generic surfaces* (upload, clarify bridge, delivery lane), not misplaced Telegram logic.

---

## 16. Relevant upstream developments (Part 14)

- Upstream has **v2026.8.19 (= v0.20.5, commit fcbd107, released Aug 21)** — one tag ahead of the installed v2026.8.18 base; ~323 PR rollup including multi-question clarify, fuzzy `/model` picker (CLI), PDF/file attachments with drag&drop (desktop tier), keyless web tier, cron jobs gaining persistent memory + per-job reasoning effort. Full curated notes deferred upstream to v0.21.0.
- **Nothing in this release removes a parity blocker** for Android (no api_server delivery lane, no upload endpoint announced). *Multi-question clarify* is relevant to the future clarify bridge design; *cron reasoning-effort* is orthogonal.
- **Recommendation:** wait. The install carries 6 local Bufanatic-integration commits; rebasing onto v2026.8.19 is worthwhile after the Bufanatic work stabilizes and before starting NEXT-D/E server work, so new generic endpoints land on a fresh base. Do not backport anything piecemeal now.

---

## 17. Prioritized roadmap

### NEXT A — Core conversational parity *(Android only; independent)*
- **Goal:** the phone sees server truth: session-backed history, visible tool activity.
- **Hermes changes:** none (endpoints exist).
- **Android changes:** extend `HermesClient` with `/api/sessions` list/get/messages; History screen gains server-backed entries; parse `hermes.tool.progress` frames → status chip replacing the stall heuristic; optional `DELETE /api/sessions/{id}` wired to conversation delete.
- **Config:** none. **Security:** bearer already required; no new exposure.
- **Tests:** wire-model unit tests (existing pattern), parser tests for progress frames; manual device pass.
- **Complexity:** M. **Risks:** session-id lifecycle decisions (D1).

### NEXT B — Model-control parity *(Android + small config; independent of A)*
- **Goal:** inspect and switch models per conversation, server-authoritative.
- **Hermes changes:** none mandatory. Owner may add `model_routes` mappings for api_server aliases (enables one-shot request-body switching) — config-only.
- **Android changes:** `GET /api/model/options` cached provider; model chip + bottom sheet on ChatScreen; persist choice via `PATCH /api/sessions/{id}` / lock API; `model` stays omitted unless the user chose (preserves tested omission contract).
- **Security:** alias names become visible; no credential impact.
- **Tests:** JSON round-trip incl. omit-vs-send (extend `ChatRequestJsonTest`); UI state tests.
- **Complexity:** S/M.

### NEXT C — Bufanatic read parity *(Hermes-fork plugin change + config; independent)*
- **Goal:** voice access to SEO planning/status, read-only.
- **Hermes changes:** split `bufanatic` toolset into `bufanatic_read` / `bufanatic_mutate` in `plugins/bufanatic/plugin.yaml` + registration (small, contained in the integration branch); add `bufanatic_read` to `platform_toolsets.api_server`.
- **Android changes:** none required (conversational); optionally a "SEO" suggestion chip.
- **Security:** read-only surface on the phone; mutations remain unreachable from api_server.
- **Tests:** plugin toolset-split tests (mirror existing `tests/plugins/test_bufanatic_plugin.py`); config validation test.
- **Complexity:** S. **Depends on:** owner decision D2.

### NEXT D — File/share parity *(new generic Hermes endpoint + Android; after base update)*
- **Goal:** share/attach documents and photos to conversations.
- **Hermes changes:** `POST /v1/files` (multipart, size caps, cache-layout reuse); message-reference convention mirroring Telegram's inline note.
- **Android changes:** share-sheet target activity; attach button; multipart upload; attachment bubbles.
- **Security:** uploads authenticated by the same bearer; consider per-file size/mime caps; agent fs reach unchanged (reads limited to what was uploaded + existing caches).
- **Then, optionally:** enable `file` toolset on api_server.
- **Complexity:** L (M server + M Android). **Dependencies:** rebase on ≥v2026.8.19 recommended.

### NEXT E — Reminder delivery / Android notifications *(two phases; phase 1 independent)*
- **Goal:** "recuérdame X mañana" actually reaches the phone.
- **Phase 1 (Android only):** create via `/api/jobs` (or conversationally); poll `GET /api/jobs`; post local notifications (new permission + receiver). Complexity S/M.
- **Phase 2 (Hermes + Android):** api_server becomes a delivery lane — authenticated SSE/long-poll device subscription fed from `_deliver_result` when target resolves to `api_server`; app subscribes in background and raises notifications. Complexity M/L.
- **Security:** bearer-scoped subscription; notifications may leak content on lockscreen — offer redacted-notification setting.

### NEXT F — Controlled mutation approvals *(after C and E-phase-2 groundwork)*
- **Goal:** approve/reject SEO actions from Android with auditable confirmation.
- **Hermes changes:** expose `bufanatic_mutate` only through the runs-approval flow (approval.request → POST approval), or equivalent per-call confirmation binding; never as free agent tools on chat endpoints initially.
- **Android changes:** approval card UI on runs events; voice confirmation restricted to strong keywords; unlocked-device requirement.
- **Security:** every decision a persisted event; bare-"sí" rejection policy; audit via existing logs.
- **Complexity:** M/L.

### NEXT G — Broader personal-assistant capabilities *(out of scope now)*
- Calendar/reminders/home-automation/server-ops enter as **new toolsets assigned explicitly per platform** following the established mechanism. No framework work beforehand (per §14).

**Independence:** A ∥ B ∥ C can proceed in parallel; E-1 parallel too; D and F benefit from a prior base update; E-2 shares infra concepts with F but neither blocks the other.

---

## 18. Proposed first implementation commits (design only — nothing committed)

**Android repo (`~/Dev/hermes-assistant`):**
1. `feat(api): add sessions discovery and history to HermesClient` — Models.kt session types + `listSessions()/getSessionMessages()`; unit tests for decode.
2. `feat(history): hydrate conversation list from Hermes sessions` — HistoryViewModel merge local+remote; empty/offline fallback to local mirror.
3. `feat(chat): render hermes.tool.progress frames as activity chips` — SSE event parsing (keep `[DONE]` semantics), replace stall heuristic; parser tests.
4. *(NEXT B)* `feat(models): per-conversation model selector backed by session model APIs` — options fetch, chip + sheet, PATCH/lock calls, omission contract preserved.

**Hermes repo (owner's integration branch, when approved):**
1. `refactor(bufanatic): split read-only and mutation toolsets` — plugin.yaml `provides_tools` groups + registration into `bufanatic_read`/`bufanatic_mutate`; update plugin tests.
2. `feat(config): expose bufanatic_read on api_server platform_toolsets` — config.yaml one-liner + validation test.
3. *(later, NEXT E-2)* `feat(api-server): device event feed for async deliveries` — authenticated SSE endpoint fed by the delivery chokepoint.

---

## 19. Open owner decisions

- **D1 — History source of truth:** keep local-first with server hydration, or move the History screen entirely to `/api/sessions`? Affects deletion semantics (local delete + remote DELETE?).
- **D2 — Bufanatic staging:** approve the read/mutate toolset split in your branch? Timing for enabling `bufanatic_read` on api_server?
- **D3 — Reminder stopgap:** accept Telegram-delivered reminders during transition, or go straight to poll-based local notifications?
- **D4 — NL model switching:** build the tiny generic `set_session_model` agent tool (upstreamable contribution?) or picker-only?
- **D5 — Update cadence:** when to rebase the 6 local commits onto v2026.8.19 (recommended: before NEXT-D server work)?
- **D6 — Transport hardening:** TLS front (reverse proxy) for api_server, and lockscreen notification redaction preference.

## 20. Explicitly NOT to implement yet

- Any client-side model routing, provider defaults, or "smart" model guessing in Android (server authority).
- Copying slash-command UX, inline keyboards, or `/model` textboxes to Android.
- Bufanatic mutations (and anything execute-class) on api_server — especially via voice.
- `file` toolset on api_server before a generic upload architecture exists.
- Terminal / code_execution / browser / computer_use / delegation / infrastructure toolsets on api_server (unchanged policy).
- Cron push infrastructure redesign beyond the delivery-lane approach (no parallel notification system).
- Memory-write approval UI (gate is off; no HTTP surface needed yet).
- Multi-user / multi-device capability frameworks, per-device policies, risk-tiered tool matrices (premature).
- Wake word, ElevenLabs, group-chat features, Home Assistant, webhook delivery senders.
- Loosening the cross-origin session-resume guard (IDOR protection) for convenience.

---

*End of audit. Generated read-only; the only artifact created is this document.*
