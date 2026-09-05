# Hermes v0.3.0 — Minimal server patch: clearing a per-session model override

**Status:** IMPLEMENTED (owner decision, 2026-08-23).

This is a historical design note. The model-clear contract is part of the
api_server and is consumed by the Android client.

---

## 1. Problem

Hermes exposes a persistent per-session model pin to API clients:

```
POST /api/sessions/{id}/model   {"model": "qwen3.6", "require_model_lock": true}
```

This writes a confirmed `browser_model_lock` into the session row's
`model_config` and wins the final model-precedence chain:

```
confirmed Browser lock → session /model override → session-persisted model →
model_routes alias → per-request model → global default
```

**There is no inverse.** An API client can pin a model but **cannot revert to
Automatic** without destroying the session. Commands that look like clears are
rejected (400 `missing_model`) rather than silently ignored.

---

## 2. The patch (v0.3.1 — generic, small, server-authoritative)

### Design decision — POST empty-selection clears

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
- Android already calls `clearSessionModel()` as **POST** to this endpoint with
  `{"model":null}`. Reusing POST means **zero Android wire-contract change**.
- The existing `runtime_lock_error` already rejects empty selections on the pin
  path, so adding a clear branch *before* that check is unambiguous.

### Server implementation

Insert in `_handle_session_model_lock` after building the runtime request and
**before** `require_model_lock = True`:

```python
requested = runtime_request.get("requested") or {}
has_selection = bool(
    _clean_runtime_id(requested.get("model"))
    or _clean_runtime_id(requested.get("provider"), max_len=80)
)
if not has_selection:
    # No model/provider selected -> release pinned override and return to
    # Automatic (gateway default) routing.
    db = await _ensure_session_db_async()
    if db is None:
        return web.json_response(_openai_error("Session database unavailable"), status=503)
    await asyncio.to_thread(db.update_session_model, session_id, None)
    return web.json_response({
        "object": "hermes.session.model_lock",
        "session_id": session_id,
        "runtime": {},
        "automatic": True,
    })
```

**Why no DB change is needed:** `SessionDB.update_session_model(session_id, None)`
already:
- merges `{"browser_model_lock": None}` → deletes `browser_model_lock` from
  `model_config`;
- runs `UPDATE sessions SET model = NULL`;
- nulls `system_prompt`/`system_prompt_hash`.

This is the same primitive Telegram `/model` uses — battle-tested.

---

## 3. Android contract

`HermesClient.clearSessionModel(sessionId)` POSTs `{"model": null}` to
`/api/sessions/{id}/model`. On the patched server this returns
`{"object": "...", "session_id": id, "automatic": true, "runtime": {}}`.
On the unpatched server it returns 400 `missing_model` — the server patch
must land before Android ships the "Automatic" release path.

## 4. Caveats & contract notes

- **"Automatic" = removal of the explicit API/session pin**, not "freeze to the
  current global default." Clearing sets the `model` column to NULL.
- **Drop only the DB pin.** The gateway's in-memory `_session_model_overrides`
  (Telegram `/model` live switch) is NOT touched.
- Clearing is idempotent.
- `runtime: {}` is a new response shape; the Android decoder tolerates it.
- **Nonexistent session** → 404. **Unauthorized** → 401.

## 5. Tests

| # | Case | Test |
|---|---|---|
| 1-2 | Pin model → read session model==pin | existing lock tests |
| 3 | `/chat` no model after pin uses pinned model | existing tests |
| 4 | Clear: POST `{"model":null}` | `test_clear_model_override_returns_automatic` |
| 5 | GET session after clear → model None | same test |
| 6 | `/chat` no model after clear uses default | `test_pin_then_clear_then_no_model_chat_uses_default` |
| 7 | Repeat clear idempotent | `test_clear_model_override_idempotent` |
| 8 | Invalid model rejected | existing tests |
| 9 | Nonexistent session → 404 | `test_clear_model_nonexistent_session_404` |
| 10 | Unauthorized → 401 | `test_clear_model_requires_auth` |
| 11 | Other session unaffected | `test_clear_model_override_preserves_other_session` |
| 12 | Telegram store untouched | code review (separate store) |

---

*Historical design note — implementation accepted and live.*