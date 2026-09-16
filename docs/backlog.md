# Backlog

1. Verify active-conversation and pending-model restoration on a real device after process death; local persistence is implemented.
2. Old-package FCM enrollment cleanup — SERVER_SIDE_FOLLOWUP: an uninstalled old applicationId may leave a stale server-side FCM enrollment; clean up server-side. No Android code change required.
3. Server-side provider name bug — SERVER_FOLLOWUP: GET /api/model/options returns the custom provider's display name as the literal string "NaN", so clients render "NaN · qwen3.6". Fix the provider-name resolution server-side. No Android change required (the client renders ProviderRow.name verbatim).
4. Add CI (GitHub Actions workflow running `:app:testDebugUnitTest`) before accepting external contributions.
