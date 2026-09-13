package dk.foss.jarvis.data

import kotlinx.serialization.Serializable
import dk.foss.jarvis.hermes.ChatTransportKind

/** A single message shown in the UI (also the in-memory unit shared by chat + voice). */
data class UiMessage(val role: String, val text: String, val isError: Boolean = false)

@Serializable
data class StoredMessage(val role: String, val text: String)

/** A full saved conversation. */
/** Sessions identity is unrelated to FCM device_id, event ids, or push protocol. */
@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val sessionId: String? = null, // Hermes X-Hermes-Session-Id, for server-side continuity
    val messages: List<StoredMessage> = emptyList(),
    val transport: ChatTransportKind? = null,
    val origin: String? = null,
    val lastUsedAt: Long? = null,
)

/** Lightweight entry for the history list. */
data class ConversationMeta(
    val id: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val sessionId: String? = null, // server session this mirror is bound to, if any
    val transport: ChatTransportKind? = null,
    val origin: String? = null,
)
