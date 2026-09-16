package dk.foss.jarvis.ui

import dk.foss.jarvis.data.ConversationMeta
import dk.foss.jarvis.hermes.ChatTransportKind

/** Local UUIDs and Hermes session IDs occupy distinct namespaces. */
internal fun findLocalSessionMirror(items: List<ConversationMeta>, sessionId: String, origin: String): String? =
    items.firstOrNull {
        it.sessionId == sessionId && it.origin == origin && it.transport == ChatTransportKind.SESSIONS
    }?.id
