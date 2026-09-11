# Backlog

1. Persist last active conversation id and restore it on cold start, falling back to a new conversation if the referenced conversation no longer exists. (next UX improvement after Sessions migration release)
2. Old-package FCM enrollment cleanup — SERVER_SIDE_FOLLOWUP: an uninstalled old applicationId may leave a stale server-side FCM enrollment; clean up server-side. No Android code change required.
3. Server-side provider name bug — SERVER_FOLLOWUP: GET /api/model/options returns the custom provider's display name as the literal string "NaN", so clients render "NaN · qwen3.6". Fix the provider-name resolution server-side. No Android change required (the client renders ProviderRow.name verbatim).
