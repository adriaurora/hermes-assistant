# Hermes v0.4.0 — Bufanatic read-only on Android (recommendation, not implemented)

**Date:** 2026-08-23 · **Status:** DESIGNED, NOT IMPLEMENTED. This phase is gated
on the v0.3.1 model-control contract being complete.

**Server audited:** `ubuntu-services` `/home/hermes/.hermes/hermes-agent`
branch `hermes-bufanatic-integration` @ `f5cd9f7de8` (after the v0.3.1 clear
commit `51635b987a`).

---

## 1. Current Bufanatic plugin state (verified by source inspection)

File: `plugins/bufanatic/` (`plugin.yaml`, `__init__.py`, `client.py`,
`context.py`, `tools.py`).

**Registration (`plugin.yaml`):** `kind: backend`, `provides_tools:` with **7**
tools. `__init__.py:register()` iterates `_TOOLS` and registers every tool with
`toolset="bufanatic"` (one flat toolset) + one read-only system-prompt section.

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
- Telegram: all 7 (via `platform_toolsets.telegram` + `known_plugin_toolsets.telegram`).
- api_server: **none** — `platform_toolsets.api_server` is
  `[web, todo, memory, clarify, cronjob, session_search]`; `bufanatic` is only in
  the *known* list, not the *enabled* list.

---

## 2. Recommended v0.4.0 boundary

### Hermes side (owner's integration branch, small)
1. **Split the toolset** in `plugin.yaml` → nested toolset groups:
   ```
   provides_tools:
     bufanatic_read:
       - list_bufanatic_pending_actions
       - get_bufanatic_action
       - get_bufanatic_metrics
       - get_bufanatic_outcomes
       - get_bufanatic_rollback_recommendations
     bufanatic_mutate:
       - approve_bufanatic_action
       - reject_bufanatic_action
   ```
   and update `__init__.py:register` to mind the toolset name per tool (pass
   `toolset=<group>` matching each `_TOOLS` entry) — the registry already takes
   a `toolset` kwarg (currently `"bufanatic"`).
2. Enable on api_server (config.yaml):
   ```yaml
   platform_toolsets:
     api_server: [web, todo, memory, clarify, cronjob, session_search, bufanatic_read]
   ```
   Do **NOT** add `bufanatic_mutate` — Android has no approval UI yet. The
   in-memory no-op `request/confirm_execution` design stays unreachable from
   api_server (it is already deliberately no-op and not registered as tools).

### Android side
- No new Android integration. Voice/text queries like "¿Qué acciones SEO tengo
  pendientes?", "Explícame la segunda", "¿Cómo están funcionando?" work through
  the normal agent loop once `bufanatic_read` is enabled on api_server.
- Optional UX later: a "SEO" suggestion chip. Not required for the read flow.
- No capability framework, no heartbeat, no mutation surface.

---

## 3. Safety
- `bufanatic_read` exposes only GET operations with zero external mutations.
- `approve`/`reject`/execute remain unreachable from api_server until a later
  phase adds the runs-approval channel (v0.4+/v0.5) — every decision logged by
  existing agent logs, so no bare-"sí" voice mutation channel exists on Android.
- No config change to model aliases, providers, STT, memory, cron, file.
- Restrictions on the v0.3.1 patch apply unchanged (api_server private host
  bind, bearer auth, no toolsets added).

---

## 4. Sequencing
1. (DONE) v0.3.1 model-clear contract.
2. In ONE later Hermes commit (owner branch): plugin toolset split + api_server
   `bufanatic_read` enable.
3. Verify on Telegram unchanged (all 7 still on telegram) + api_server read only:
   `platform_toolsets.api_server` has `bufanatic_read`, and `GET /v1/toolsets`
   reflects the new group.
4. Android v0.4.0 ships the UX (suggestion chip, status) only after the server
   split is proven.