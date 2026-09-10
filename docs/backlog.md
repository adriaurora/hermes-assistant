# Backlog

1. Persist last active conversation id and restore it on cold start, falling back to a new conversation if the referenced conversation no longer exists. (next UX improvement after Sessions migration release)
2. Old-package FCM enrollment cleanup — SERVER_SIDE_FOLLOWUP: an uninstalled old applicationId may leave a stale server-side FCM enrollment; clean up server-side. No Android code change required.
