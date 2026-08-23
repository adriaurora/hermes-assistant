# Hermes v0.3.0 — Minimal server patch: clearing a per-session model override

**Status:** Owner decision taken (2026-08-23) → implement the smallest generic
Hermes-side addition that lets the API platform return a session to
"Automatic" (gateway default) model routing.

**Server audited:** `ubuntu-services` `/home/hermes/.hermes/hermes-agent`
branch `main` (@ `1778d503de` = `v2026.8.19-150-g1778d503de`).

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

| Attempt at clearing | Result |
|---|---|
| `PATCH /api/sessions/{id}` with `{"model": null}` | 400 `unsupported_session_field` (allowed set has no model) |
| `POST /api/sessions/{id}/model` with `{"model": null}` | 200 success but **silent no-op** — `_persist_session_runtime_lock` returns early when `require_model_lock` is false; the existing lock stays |
| `POST /api/sessions {"id": ..., "model": ...}` | 409 — session already exists; create cannot update |
| Telegram/CLI `/new` (which clears the gateway in-memory override) | not exposed by api_server |

So today an API client can pin a model but **cannot revert to Automatic**
without destroying the session. The v0.3.0 "Automatic → clear override →
Hermes regains control" flow has no wire mechanism.

---

## 2. The patch (generic, small, server-authoritative)

### 2a. `hermes_state.py` — add `clear_session_runtime_lock`

Add next to `update_session_runtime_lock` (≈ line 7681):

```python
    def clear_session_runtime_lock(self, session_id: str) -> None:
        """Remove the persistent per-session model lock, returning the session
        to the gateway default/Automatic model selection.

        Mirrors ``update_session_runtime_lock``'s merge discipline so lineage
        markers (``_branched_from``/``_delegate_from``) survive, and nulls the
        ``model`` column + cached system prompt so a stale pin can never be
        re-read as a raw ``session_model`` override.
        """
        def _do(conn):
            merged = self._merge_model_config_json(
                conn, session_id, {"browser_model_lock": {}}
            )
            if merged is _MODEL_CONFIG_ROW_MISSING:
                return
            conn.execute(
                """UPDATE sessions SET
                   model_config = ?,
                   model = NULL,
                   system_prompt = NULL,
                   system_prompt_hash = NULL
                   WHERE id = ?""",
                (merged, session_id),
            )
            self._delete_unreferenced_system_prompts(conn)
        self._execute_write(_do)
```

The empty `browser_model_lock: {}` is read back by
`api_server._runtime_request_from_persisted_session_lock` where
`lock.get("confirmed")` is falsy → no lock applies → Automatic.

### 2b. `gateway/platforms/api_server.py` — accept `model: null` as "clear"

In `_handle_session_model_lock` (≈ line 4922), right after
`runtime_request = self._session_runtime_request_from_body(body)`, before
`runtime_request["require_model_lock"] = True`:

```python
        # Explicit clear: a body without model/provider returns this session
        # to Automatic (gateway default). The regular lock path below refuses
        # empty selections, so the client must be able to actively release
        # the override it previously set (v0.3.0 parity).
        requested = runtime_request.get("requested") or {}
        has_selection = bool(
            self._clean_runtime_id(requested.get("model"))
            or self._clean_runtime_id(requested.get("provider"), max_len=80)
        )
        if not has_selection:
            db = await self._ensure_session_db_async()
            if db is None:
                return web.json_response(
                    _openai_error(
                        "Session database unavailable",
                        code="session_db_unavailable",
                    ),
                    status=503,
                )
            await asyncio.to_thread(db.clear_session_model_lock, session_id)
            return web.json_response({
                "object": "hermes.session.model_lock",
                "session_id": session_id,
                "runtime": {},
                "automatic": True,
            })
```

(Exact line numbers will shift; the two anchors above are what matter.)

---

## 3. Android contract (consumes the patch)

`HermesClient.clearSessionModel(sessionId)` POSTs `{"model": null}` to
`/api/sessions/{id}/model`. On the patched server this returns
`{"object": "...", "session_id": id, "automatic": true, "runtime": {}}`.
On the current unpatched server it returns 200 with the same shape but
**does nothing** — that is why the server patch must land before Android
ships the "Automatic" release path.

## 4. Caveats

- Do **not** gate this on the browser-extension feature flag: the lock
  endpoint itself is already bearer-authed and session-scoped.
- The returned `runtime: {}` shape is new; the Android decoder is tolerant
  (`ignoreUnknownKeys`) and treats it as Automatic.
- Optional, not required: same clear semantics could later be exposed via
  `PATCH /api/sessions/{id}` (add `model` to `allowed`), but the model-lock
  endpoint is the semantically correct home already used by the UI.