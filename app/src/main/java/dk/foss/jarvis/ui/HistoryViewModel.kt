package dk.foss.jarvis.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationMeta
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dk.foss.jarvis.net.E2eLog

enum class HistoryOrigin { LOCAL, SERVER_PHONE, SERVER_OTHER }

/** One row of the merged history: a local mirror or a server-only Hermes session. */
data class HistoryEntry(
    val key: String,
    val title: String,
    val updatedAt: Long,
    val messageCount: Int,
    val origin: HistoryOrigin,
    val originTag: String?,
    val localId: String?,
    val serverSessionId: String?,
)

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = ConversationRepository.get(app)

    val items = mutableStateOf<List<HistoryEntry>>(emptyList())
    val notice = mutableStateOf<String?>(null)

    fun refresh() {
        viewModelScope.launch {
            notice.value = null
            val s = settingsStore.settings.first()
            val local = repo.list()
            val remote = fetchServerSessions()
            items.value = merge(local, remote)
        }
    }

    private suspend fun fetchServerSessions(): List<SessionSummary> {
        val s = settingsStore.settings.first()
        if (!s.isConfigured) return emptyList()
        return HermesClient(s.baseUrl, s.apiKey).listSessions(limit = 100).fold(
            onSuccess = { it.data },
            onFailure = {
                notice.value = "Hermes unreachable, showing on-device history only"
                emptyList()
            },
        )
    }

    private fun merge(local: List<ConversationMeta>, remote: List<SessionSummary>): List<HistoryEntry> {
        val entries = mutableListOf<HistoryEntry>()
        val boundSessions = mutableSetOf<String>()
        for (m in local) {
            m.sessionId?.let { boundSessions.add(it) }
            entries.add(
                HistoryEntry(
                    key = m.id,
                    title = m.title.ifBlank { "Conversation" },
                    updatedAt = m.updatedAt,
                    messageCount = m.messageCount,
                    origin = HistoryOrigin.LOCAL,
                    originTag = null,
                    localId = m.id,
                    serverSessionId = null,
                ),
            )
        }
        for (s in remote) {
            if (s.id in boundSessions) continue // already represented by its local mirror
            entries.add(
                HistoryEntry(
                    key = "srv:${s.id}",
                    title = s.title?.ifBlank { null }
                        ?: s.preview?.ifBlank { null }?.take(60)
                        ?: "Hermes session …${s.id.takeLast(6)}",
                    updatedAt = s.last_active ?: s.ended_at ?: s.started_at ?: 0L,
                    messageCount = s.message_count,
                    origin = if (s.source == "api_server") HistoryOrigin.SERVER_PHONE else HistoryOrigin.SERVER_OTHER,
                    originTag = s.source?.takeIf { it != "api_server" && it.isNotBlank() }?.let { prettySource(it) },
                    localId = null,
                    serverSessionId = s.id,
                ),
            )
        }
        return entries.sortedByDescending { it.updatedAt }
    }

    /** Persist the current conversation, load the chosen one, then continue. */
    fun open(id: String, onReady: () -> Unit) {
        viewModelScope.launch {
            repo.persist()
            repo.open(id)
            E2eLog.log("historyOpen id=${repo.activeConversationId} transport=${repo.transport} sessionId=${repo.sessionId}")
            // If this is a SESSIONS conversation bound to the current server,
            // try to refresh messages from the server (authoritative copy).
            val s = settingsStore.settings.first()
            if (s.isConfigured && repo.transport == ChatTransportKind.SESSIONS && repo.sessionId != null) {
                val client = HermesClient(s.baseUrl, s.apiKey)
                val gate = repo.verifySessionForCurrentOrigin(client, s.baseUrl, s.apiKey)
                if (gate is ConversationRepository.RebindOutcome.BlockedAuth) {
                    notice.value = "Authentication failed while verifying this session."
                    onReady(); return@launch
                }
                if (gate is ConversationRepository.RebindOutcome.BlockedMissing) {
                    notice.value = "Remote session no longer exists (session_not_found). Start a new conversation from History to continue."
                    onReady(); return@launch
                }
                if (gate is ConversationRepository.RebindOutcome.BlockedRetryable) {
                    notice.value = "Couldn't verify history; try again."
                    onReady(); return@launch
                }
                client.getSessionMessages(repo.sessionId!!).fold(
                    onSuccess = { page ->
                        val msgs = page.data
                            .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
                            .map { dk.foss.jarvis.hermes.ChatMessage(it.role, it.content) }
                        if (msgs.isNotEmpty()) {
                            repo.replaceAllMessages(msgs.map { dk.foss.jarvis.data.UiMessage(it.role, it.content) })
                        }
                    },
                    onFailure = { err: Throwable ->
                        val hermesErr = err as? HermesHttpError
                        if (hermesErr?.isSessionMissing == true) {
                            notice.value = "Remote session no longer exists (session_not_found). Start a new conversation from History to continue."
                        } else {
                            notice.value = "Couldn't refresh history; showing saved copy."
                        }
                    }
                )
            }
            onReady()
        }
    }

    /** Hydrate a server-only session into a local mirror, then continue in chat. */
    fun openServer(serverSessionId: String, entryTitle: String, createdAtMs: Long, onReady: () -> Unit) {
        viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (!s.isConfigured) { notice.value = "Configure Hermes in Settings first"; return@launch }
            val client = HermesClient(s.baseUrl, s.apiKey)
            val origin = originIdentity(s.baseUrl, s.apiKey)

            // Resolve capabilities to pick the right transport — fail-closed on UNKNOWN.
            val caps = CapabilityRegistry.capabilities(origin) { client.getCapabilities() }
            val transport = when {
                caps.state == CapabilityState.SUPPORTED && caps.features.session_chat -> ChatTransportKind.SESSIONS
                else -> null // UNKNOWN, unsupported, or explicit session_chat=false → fail closed
            }
            if (transport == null) {
                notice.value = "Server capabilities could not be verified. Session import skipped."
                return@launch
            }

            client.getSessionMessages(serverSessionId).fold(
                onSuccess = { page ->
                    val msgs = page.data
                        .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
                        .map { dk.foss.jarvis.hermes.ChatMessage(it.role, it.content) }
                    if (msgs.isEmpty()) {
                        notice.value = "This session has no readable messages"
                        return@fold
                    }
                    repo.persist()
                    repo.importServerSession(serverSessionId, entryTitle, createdAtMs, msgs, origin = origin, transport = transport)
                    onReady()
                },
                onFailure = { err: Throwable -> notice.value = "Could not load session: ${err.message?.take(120)}" },
            )
        }
    }

    fun startNew(onReady: () -> Unit) {
        viewModelScope.launch {
            repo.persist()
            repo.startNew()
            onReady()
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            repo.delete(id)
            refresh()
        }
    }

    private fun prettySource(source: String): String = when (source.lowercase()) {
        "cli" -> "CLI"
        else -> source.replaceFirstChar { it.uppercase() }
    }
}
