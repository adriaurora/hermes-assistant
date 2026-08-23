# Hermes v0.4.0 — Bufanatic read-only on Android (implementation record)

**Date:** 2026-08-23 · **Status:** IMPLEMENTED AND LIVE-VERIFIED, 2026-08-23.

**Server audited:** `ubuntu-services` `/home/hermes/.hermes/hermes-agent`
branch `hermes-bufanatic-integration` @ `6fda084763` (split commit), built on
`60df328f56`.

---

## 1. Current Bufanatic plugin state (verified by source inspection)

File: `plugins/bufanatic/` (`plugin.yaml`, `__init__.py`, `client.py`,
`context.py`, `tools.py`).

**Registration (`plugin.yaml`):** `kind: backend`, `provides_tools:` with **7**
tools, listed flat under `provides_tools` (typed `List[str]`; nested groups are
NOT supported by `PluginManifest`, so the split is done in
`__init__.py::register()`). That `register()` now registers the 5 reads under
`toolset="bufanatic_read"` and the 2 mutations under `toolset="bufanatic_mutate"`.

**Tool inventory (tools.py):**

| Tool | Class | Evidence |
|---|---|---|
| `list_bufanatic_pending_actions` | READ (GET `/planning/pending`) | `_handle_bufanatic_pending_actions` (L131) |
| `get_bufanatic_action` | READ (GET `/actions/{id}`) | L159 |
| `get_bufanatic_metrics` | READ (GET `/actions/metrics`) | L191 |
| `get_bufanatic_outcomes` | READ (GET `/seo/outcomes`) | L209 |
| `get_bufanatic_rollback_recommendations` | READ (GET `/seo/outcomes/rollbacks`) | L223 |
| `approve_bufanatic_action` | MUTATION (exactly one POST + conditional execute POST, human-confirmation gated) | L322, L565-602 |
| `reject_bufanatic_action` | MUTATION (exactly one POST) | L417 |

Reads use **GET only** (client `_get`), zero external side effects.

**Current platform exposure:**
- Telegram: all 7 (via `platform_toolsets.telegram` + `known_plugin_toolsets.telegram`): `bufanatic_read` + `bufanatic_mutate`.
- api_server: read-only — `platform_toolsets.api_server` = `[web, todo, memory, clarify, cronjob, session_search, bufanatic_read]`; `bufanatic_mutate` present only in `known_plugin_toolsets.api_server` (pinned disabled).
- cli/cron: unchanged.

---

## 2. Implemented boundary

### Hermes side

1. **Split via `plugins/bufanatic/__init__.py::register()`** — per-tool
   `toolset` kwarg; no duplicate implementations; handlers/schemas/client/system-prompt
   unchanged; no nested plugin.yaml (manifest field is a flat `List[str]`).

2. **`config.yaml` (live `/home/hermes/.hermes/config.yaml`)** changed:

   - `platform_toolsets.api_server:`
     `[web, todo, memory, clarify, cronjob, session_search, bufanatic_read]`
     (No `bufanatic_mutate`).
   - `platform_toolsets.telegram:`
     `[...existing..., bufanatic_read, bufanatic_mutate]`.
   - `known_plugin_toolsets.telegram:`
     `[spotify, bufanatic_read, bufanatic_mutate]`.
   - `known_plugin_toolsets.api_server:`
     `[spotify, a2a, bufanatic_read, bufanatic_mutate]` (mutate pinned
     KNOWN-but-disabled so the plugin-default-on rule cannot enable it on
     api_server).
   - cli/cron unchanged.

### Android side

- No code change. Voice queries like "¿Qué acciones SEO tengo pendientes?",
  "Explícame la segunda", "¿Cómo están funcionando?" work through the normal
  agent loop once `bufanatic_read` is enabled on api_server.

---

## 3. Safety — verification results

- **Tests:** 206 pytest passed (bufanatic_plugin, new `test_bufanatic_toolset_split`,
  user_task propagation, tools_config), plus api_server toolset/plugin/config
  suites green.
- **Runtime `GET /v1/toolsets` (api_server):** `bufanatic_read enabled=true`
  (5 tools), `bufanatic_mutate enabled=false` (approve/reject).
- **Conversational:** "Aprueba la primera acción" → Hermes refuses ("aprobación no
  disponible en este canal"); no mutation tool invoked.
- **Log audit:** all api_server sessions only call `list_bufanatic_*`/`get_bufanatic_*`;
  the only approve observed traces to the owner's Telegram session (12:06:15,
  approver hermes), action 2915dcd3 approved but execution blocked 403 (staging
  guard), metrics `executed` unchanged.

---

## 4. Sequencing

1. (DONE) v0.3.1 model-clear contract.
2. (DONE) In ONE later Hermes commit (owner branch): plugin toolset split +
   api_server `bufanatic_read` enable.
3. (DONE) Verify on Telegram unchanged (all 7 still on telegram) + api_server
   read only: `platform_toolsets.api_server` has `bufanatic_read`, and
   `GET /v1/toolsets` reflects the new group.
4. **Android v0.4.0 UX deferred.** No chip added: Idle voice screen has no
   text-prompt path; a chip would need new plumbing.

The ordinal / never-invent-IDs rules are preserved: live ordinal test
(`list` → "Explícame la segunda" → `get_bufanatic_action` correct ID; "la séptima"
refused without tool call; ambiguous no-list case lists first).