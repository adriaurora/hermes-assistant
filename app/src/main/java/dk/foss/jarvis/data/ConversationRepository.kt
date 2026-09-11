package dk.foss.jarvis.data

import android.content.Context
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import dk.foss.jarvis.hermes.ChatMessage
import dk.foss.jarvis.hermes.ChatTransportKind
import dk.foss.jarvis.hermes.HermesClient
import dk.foss.jarvis.hermes.HermesHttpError
import dk.foss.jarvis.hermes.originIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import dk.foss.jarvis.net.E2eLog

/**
 * The single source of truth for the *active* conversation, shared by both the
 * text chat and voice modes so messages and the Hermes session id stay unified.
 * Persists to [ConversationStore] so conversations can be reopened and continued.
 */
class ConversationRepository internal constructor(private val store: ConversationStore) {

    sealed class RebindOutcome {
        object VerifiedRebound : RebindOutcome()
        object NotNeeded : RebindOutcome()
        data class BlockedAuth(val error: Throwable) : RebindOutcome()
        data class BlockedMissing(val error: Throwable) : RebindOutcome()
        data class BlockedRetryable(val error: Throwable) : RebindOutcome()
    }
    private val switchListeners = mutableListOf<() -> Unit>()
    fun onConversationSwitched(listener: () -> Unit) { switchListeners += listener }
    private fun switched() { switchListeners.toList().forEach { it() } }

    // App-lifetime scope so a fire-and-forget save survives a ViewModel being cleared
    // (viewModelScope is cancelled BEFORE onCleared runs, which would drop the last save).
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val messages: SnapshotStateList<UiMessage> = mutableStateListOf()

    // sessionId/dirty are written from the SSE callback thread and read on main.
    @Volatile var sessionId: String? = null
        private set
    @Volatile var transport: ChatTransportKind? = null
        private set
    @Volatile var origin: String? = null
        private set
    @Volatile var lastUsedAt: Long? = null
        private set

    private var activeId: String = UUID.randomUUID().toString()
    val activeConversationId: String get() = activeId
    private var title: String = ""
    private var createdAt: Long = System.currentTimeMillis()
    @Volatile private var dirty = false

    fun startNew() {
        activeId = UUID.randomUUID().toString()
        E2eLog.log("startNew newId=$activeId")
        messages.clear()
        sessionId = null
        transport = null; origin = null; lastUsedAt = null
        title = ""
        createdAt = System.currentTimeMillis()
        dirty = false
        switched()
    }

    suspend fun open(id: String) {
        val c = store.load(id) ?: return
        activeId = c.id
        title = c.title
        createdAt = c.createdAt
        sessionId = c.sessionId
        transport = c.transport; origin = c.origin; lastUsedAt = c.lastUsedAt
        messages.clear()
        messages.addAll(c.messages.map { UiMessage(it.role, it.text) })
        E2eLog.log("convOpen id=${c.id} transport=${c.transport} sessionId=${c.sessionId} msgs=${c.messages.size}")
        dirty = false
        switched()
    }

    /**
     * Replace the active conversation with a local mirror of a server-side Hermes
     * session (history parity). The mirror binds to the server session id, so the
     * next request continues that session; it persists on the next save.
     */
    fun importServerSession(sessionId: String, title: String, createdAtMs: Long, msgs: List<ChatMessage>, origin: String? = null, transport: ChatTransportKind? = null) {
        startNew()
        this.sessionId = sessionId
        this.title = title.take(60)
        this.createdAt = createdAtMs
        if (origin != null) this.origin = origin
        if (transport != null) this.transport = transport
        messages.addAll(msgs.map { UiMessage(it.role, it.content) })
        dirty = true
    }

    fun bindSession(origin: String, sessionId: String, transport: ChatTransportKind) {
        E2eLog.log("bindSession sid=$sessionId transport=$transport")
        // An existing identity may only be changed by verifySessionForCurrentOrigin.
        if (this.sessionId == null || this.origin == origin) this.origin = origin
        this.sessionId = sessionId; this.transport = transport; dirty = true
    }
    /** Bind origin + transport before the first server call, so a failed session creation stays retryable as Sessions. */
    fun bindTransport(origin: String, transport: ChatTransportKind) {
        if (sessionId == null || this.origin == origin) this.origin = origin
        this.transport = transport; dirty = true
    }

    /** Verify an existing Sessions id with the current credentials before rebinding it. */
    suspend fun verifySessionForCurrentOrigin(client: HermesClient, baseUrl: String, apiKey: String): RebindOutcome {
        val sid = sessionId ?: return RebindOutcome.NotNeeded
        if (transport != ChatTransportKind.SESSIONS) return RebindOutcome.NotNeeded
        val current = originIdentity(baseUrl, apiKey)
        if (origin == current) return RebindOutcome.NotNeeded
        return client.getSession(sid).fold(
            onSuccess = {
                origin = current
                store.rebindOrigin(activeId, current)
                dirty = false
                RebindOutcome.VerifiedRebound
            },
            onFailure = { e ->
                val h = e as? HermesHttpError
                when {
                    h?.isAuth == true -> RebindOutcome.BlockedAuth(e)
                    h?.isSessionMissing == true || h?.code == 404 -> RebindOutcome.BlockedMissing(e)
                    else -> RebindOutcome.BlockedRetryable(e)
                }
            }
        )
    }
    fun markTransport(kind: ChatTransportKind) { transport = kind; dirty = true }
    fun markUsed() { lastUsedAt = System.currentTimeMillis(); dirty = true }
    fun lastUserTurnText(): String? = messages.lastOrNull { it.role == "user" && !it.isError }?.text
    /** Queue a first Sessions turn, reusing an identical unsent bubble after a failed create. */
    fun queueFirstTurn(text: String): String {
        if (sessionId == null) {
            val last = messages.lastOrNull { !it.isError }
            if (last?.role == "user" && last.text == text) return text
        }
        addMessage("user", text)
        return text
    }
    fun replaceAllMessages(items: List<UiMessage>) { messages.clear(); messages.addAll(items); dirty = true }

    fun setSessionId(id: String) {
        if (sessionId != id) { sessionId = id; dirty = true }
    }

    /** Append a message and return its index. */
    fun addMessage(role: String, text: String, isError: Boolean = false): Int {
        if (title.isEmpty() && role == "user" && text.isNotBlank()) title = text.take(60)
        messages.add(UiMessage(role, text, isError))
        dirty = true
        return messages.lastIndex
    }

    fun appendToMessage(index: Int, delta: String) {
        if (index in messages.indices) {
            val cur = messages[index]
            messages[index] = cur.copy(text = cur.text + delta)
            dirty = true
        }
    }

    fun replaceMessage(index: Int, text: String, isError: Boolean = false) {
        if (index in messages.indices) {
            messages[index] = messages[index].copy(text = text, isError = isError)
            dirty = true
        }
    }

    /** History (non-error) as Hermes chat messages for building a request. */
    fun historyForRequest(): List<ChatMessage> =
        messages.filter { !it.isError }.map { ChatMessage(it.role, it.text) }

    suspend fun persist() {
        if (!dirty) return
        if (messages.none { !it.isError }) return
        store.save(
            Conversation(
                id = activeId,
                title = title.ifEmpty { "Conversation" },
                createdAt = createdAt,
                updatedAt = System.currentTimeMillis(),
                sessionId = sessionId,
                messages = messages.filter { !it.isError }.map { StoredMessage(it.role, it.text) },
                transport = transport,
                origin = origin,
                lastUsedAt = lastUsedAt,
            ),
        )
        dirty = false
    }

    /** Fire-and-forget save on the app-lifetime scope (safe to call at teardown). */
    fun persistAsync() {
        ioScope.launch { persist() }
    }

    suspend fun list(): List<ConversationMeta> = store.list()

    suspend fun delete(id: String) {
        E2eLog.log("delete id=$id")
        store.delete(id)
        if (id == activeId) startNew()
    }


    companion object {
        @Volatile private var instance: ConversationRepository? = null
        fun get(context: Context): ConversationRepository =
            instance ?: synchronized(this) {
                instance ?: ConversationRepository(ConversationStore(context)).also { instance = it }
            }
    }
}
