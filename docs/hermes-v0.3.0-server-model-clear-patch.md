# Hermes v0.3.0 — Minimal server patch: clearing a per-session model override

**Status:** Owner decision taken (2026-08-23) → implement the smallest generic
Hermes-side addition that lets the API platform return a session to
"Automatic" (gateway default) model routing.

**Server audited (re-audit for v0.3.1):** `ubuntu-services`
`/home/hermes/.hermes/hermes-agent` branch `hermes-bufanatic-integration`
(@ `71b7be7fe9` = `v2026.8.19-151-g71b7be7fe9`). Upstream origin maintained
at `933c209e96` (still post-tag, v2026.8.19 is newest tag; no upstream model-
clear solution exists anywhere in history or main).

**Original document:** v0.3.0 phase. Superseded by the v0.3.1 design below.

---

## 1. Problem (verified against the running install)

Hermes exposes a persistent per-session model pin to API clients:

```
POST /api/sessions/{id}/model   {"model": "qwen3.6", "require_model_lock": true}
```

This writes a confirmed `browser_model_lock` into the session row's
`model_config` (via `hermes_state.SessionDB.update_session_runtime_lock`)
and wins the final model-precedence chain:

```
confirmed Browser lock → session /model override → session-persisted model →
model_routes alias → per-request model → global default
```

**There is no inverse.** The following are all checked against the audited
install:

| Attempt at clearing | Current (unpatched) behavior |
|---|---|
| `PATCH /api/sessions/{id}` with `{"model": null}` | 400 `unsupported_session_field` (allowed set has no model) |
| `POST /api/sessions/{id}/model` with `{"model": null}` | 400 `missing_model` — handler force-sets `require_model_lock=True`, then `_runtime_lock_error` rejects an empty selection before any persist happens |
| `POST /api/sessions {"id": ..., "model": ...}` | 409 — session already exists; create cannot update |
| Telegram/CLI `/new` (which clears the gateway in-memory override) | not exposed by api_server |

So today an API client can pin a model but **cannot revert to Automatic**
without destroying the session. Commands that look like clears are rejected
rather than silently ignored, which is safer but still a missing capability.
The v0.3.0 "Automatic → clear override → Hermes regains control" flow has no
wire mechanism.

---

## 2. The patch (v0.3.1 — generic, small, server-authoritative)

### Design decision — POST empty-selection clears (recommended)

**Chosen contract (reuses the existing POST endpoint; no new route):**

```
# Pin / change an explicit selection (unchanged)
POST /api/sessions/{id}/model     {"model":"qwen3.6", "provider":"custom"}
→ 200 {"object":"hermes.session.model_lock","session_id":id,"runtime":...,"automatic":false}

# Clear the override -> Automatic (NEW branch)
POST /api/sessions/{id}/model     {}          # empty body
POST /api/sessions/{id}/model     {"model":null}
→ 200 {"object":"hermes.session.model_lock","session_id":id,"runtime":{},"automatic":true}
```

**Why POST-empty (not DELETE):**
- Android already calls `clearSessionModel()` as **POST** `/api/sessions/{id}/model` with a literal `{"model":null}` body. Reusing POST means **zero Android wire-contract change**.
- The endpoint is already bearer-gated, session-scoped, and 404s on missing sessions; a POST branch inherits all of that for free. DELETE would need a new route + test + auth wiring for no architectural gain.
- The existing `runtime_lock_error` already rejects empty selections on the *pin* path, so adding a clear branch *before* that check is unambiguous: **no model AND no provider selected ⇒ clear**, otherwise ⇒ pin/lock. There is no legal body that could be misread as both.

### 2a. `gateway/platforms/api_server.py` — clear branch in `_handle_session_model_lock`

Insert immediately after `runtime_request = self._session_runtime_request_from_body(body)` (≈ line 4934) and **before** `runtime_request["require_model_lock"] = True`:

```python
        requested = runtime_request.get("requested") or {}
        has_selection = bool(
            self._clean_runtime_id(requested.get("model"))
            or self._clean_runtime_id(requested.get("provider"), max_len=80)
        )
        if not has_selection:
            # No model/provider selected -> release any pinned override and
            # return this session to Automatic (gateway default) routing. The
            # shared mid-session writer keeps lineage markers and clears the
            # row `model` column; dropping browser_model_lock means a later
            # global default/routing change still affects this session.
            db = await self._ensure_session_db_async()
            if db is None:
                return web.json_response(
                    _openai_error(
                        "Session database unavailable",
                        code="session_db_unavailable",
                    ),
                    status=503,
                )
            await asyncio.to_thread(db.update_session_model, session_id, None)
            return web.json_response({
                "object": "hermes.session.model_lock",
                "session_id": session_id,
                "runtime": {},
                "automatic": True,
            })
```

**Why no DB change is needed:** `SessionDB.update_session_model(session_id, model, provider=None)` (hermes_state.py ≈L7489) already:
- merges a `{"browser_model_lock": None}` patch ⇒ **deletes** `browser_model_lock` from `model_config` (same `_merge_model_config_json` discipline that keeps `_branched_from`/`_delegate_from` lineage markers),
- runs `UPDATE sessions SET model = ? ...` with the given model ⇒ passing `model=None` sets the `model` column to **NULL** (this is the real "override = NONE" state),
- nulls `system_prompt`/`system_prompt_hash` so stale `Model:`/`Provider:` footers can't be replayed.

This is the same primitive `slash_commands.py` (Telegram `/model`) uses (L1983, L2294), so it is battle-tested and upstream-shaped. **No new method, no new table column.**

### 2b. (No DB change in this revision)

`hermes_state.py` is left untouched — `update_session_model` is reused as-is.

---

## 3. Android contract (consumes the patch)

`HermesClient.clearSessionModel(sessionId)` POSTs `{"model": null}` to
`/api/sessions/{id}/model`. On the patched server this returns
`{"object": "...", "session_id": id, "automatic": true, "runtime": {}}`.
On the current (unpatched) server it returns 400 `missing_model` — that is why
the server patch must land before Android ships the "Automatic" release path.

## 4. Caveats & contract notes

- **"Automatic" = removal of the explicit API/session pin**, not "freeze to
  the current global default." Clearing sets the `model` column to NULL and
  deletes `browser_model_lock`, so future global default/routing changes
  still apply to the session. This is the invariant Android must satisfy.
- **Drop only the DB pin.** The gateway's *in-memory* `_session_model_overrides`
  (Telegram `/model` live switch) is a separate store and is intentionally
  NOT touched — the API path has no gateway runner override to clear, and
  holding Telegram's override intact is required (regression check).
- Clearing is idempotent: `update_session_model(sid, None)` on a session with
  no lock simply leaves `model` NULL; the API returns `automatic:true` either
  way. Documented (not an error).
- `runtime: {}` is a new response shape; the Android decoder tolerates it
  (`ignoreUnknownKeys`) and maps it to Automatic.
- **Repeated clear** is safe (idempotent). **Nonexistent session** → 404 via
  `_get_existing_session_or_404` (checked before the clear branch).
  **Invalid JSON body** → 400 via `_read_json_body`.
  **Unauthorized** → 401 via `_check_auth`. **Invalid model string** on the
  pin path is unchanged (still routed/rejected by the existing lock logic).
- No change to: model aliases, provider config, `model_routes`,
  `direct_model_requests`, `allow_bare_model`, toolsets, STT, memory, cron,
  file, Bufanatic, API key, or WireGuard.

## 5. Tests (added in Hermes `tests/gateway/test_session_api.py`)

| # | Case | Test added |
|---|---|---|
| 1-2 | Pin model → read session model==pin | existing lock tests cover the pin path; `test_pin_then_clear_then_no_model_chat_uses_default` verifies GET after pin |
| 3 | `/chat` no model after pin uses pinned model | covered by existing `test_session_model_lock_endpoint_then_chat_reuses_persisted_lock...` |
| 4 | Clear: POST `{"model":null}` | `test_clear_model_override_returns_automatic` → 200, `automatic:true`, `runtime=={}` |
| 5 | GET session after clear → model None | `test_clear_model_override_returns_automatic` |
| 6 | `/chat` no model after clear uses default | `test_pin_then_clear_then_no_model_chat_uses_default` → `session_model is None`, `route is None` |
| 7 | Repeat clear idempotent | `test_clear_model_override_idempotent` |
| 8 | Invalid model rejected | existing lock tests (`_runtime_lock_error` unchanged) |
| 9 | Nonexistent session | `test_clear_model_nonexistent_session_404` → 404 |
| 10 | Unauthorized | `test_clear_model_requires_auth` (auth_adapter) → 401 |
| 11 | Other session unaffected | `test_clear_model_override_preserves_other_session` |
| 12 | Telegram store untouched | covered by code-review (separate `_session_model_overrides` store; no code path touches it) |

Result on patched server: **26/26 pass** (`tests/gateway/test_session_api.py`).
On the unpatched api_server.py, tests 1-4 fail with `400 == 200` (the clear
branch does not exist), confirming the new tests exercise the branch.