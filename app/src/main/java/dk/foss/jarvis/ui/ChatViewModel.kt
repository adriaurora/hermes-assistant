package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.HermesClient
import dk.foss.jarvis.hermes.ModelOptionsPayload
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = ConversationRepository.get(app)
    private val main = Handler(Looper.getMainLooper())

    /** Run on the main thread; returns Unit so it fits expression-body callbacks. */
    private fun onMain(block: () -> Unit) { main.post(block) }

    val messages get() = repo.messages
    val isStreaming = mutableStateOf(false)
    val notConfigured = mutableStateOf(false)

    /** Label of the tool Hermes is running right now, from hermes.tool.progress frames. */
    val activity = mutableStateOf<String?>(null)

    // --- per-conversation model selection (server-authoritative) ---
    // Hermes owns the model catalog and the per-session override. Android only
    // lists what the server advertises and pins it via the session model API;
    // it never routes or guesses provider/model ids itself.

    /** Display label shown on the compact model chip, e.g. "Automatic" or a model id. */
    val modelLabel = mutableStateOf("Automatic")
    /** Default model the server would use when no override is set (its "Automatic" choice). */
    val modelDefault = mutableStateOf<String?>(null)
    /** Options returned by GET /api/model/options (flattened), plus the implicit Automatic. */
    val modelOptions = mutableStateOf<List<ModelOption>>(emptyList())
    val modelPickerOpen = mutableStateOf(false)
    val modelLoading = mutableStateOf(false)
    val modelError = mutableStateOf<String?>(null)

    /** Model requested while no server session existed yet; applied on first session id. */
    private var pendingOption: ModelOption? = null

    private var currentSource: EventSource? = null

    /** Fetch the Hermes catalog and, when a session exists, the current pinned model. */
    fun refreshModel() {
        viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (!s.isConfigured) { modelLoading.value = false; return@launch }
            modelLoading.value = true
            val client = HermesClient(s.baseUrl, s.apiKey)
            client.getModelOptions().fold(
                onSuccess = {
                    modelOptions.value = flattenModels(it)
                    modelDefault.value = it.model
                    modelLoading.value = false
                    syncLabelWithServer(client)
                },
                onFailure = {
                    modelLoading.value = false
                    modelError.value = "Models unavailable: ${it.message?.take(120)}"
                },
            )
        }
    }

    /** Choose [option], or pass null for Automatic (clear the session override). */
    fun chooseModel(option: ModelOption?) {
        viewModelScope.launch {
            modelError.value = null
            val sid = repo.sessionId
            val s = settingsStore.settings.first()
            if (!s.isConfigured) { modelError.value = "Configure Hermes in Settings first"; return@launch }

            // Reflect the choice optimistically; the server confirms below.
            val chosenLabel = option?.label ?: "Automatic"
            val client = HermesClient(s.baseUrl, s.apiKey)

            if (sid.isNullOrEmpty()) {
                // No server session yet — remember the intent; it is applied on the
                // first turn when X-Hermes-Session-Id arrives.
                if (option != null) pendingOption = option
                modelLabel.value = chosenLabel
                modelPickerOpen.value = false
                return@launch
            }

            val result = if (option == null) client.clearSessionModel(sid)
            else client.setSessionModel(sid, option.modelId, option.providerSlug)

            result.fold(
                onSuccess = {
                    modelLabel.value = chosenLabel
                    modelPickerOpen.value = false
                },
                onFailure = {
                    // No silent fallback: a failed model change stays visible as an error
                    // and the sheet stays open so the user can retry or dismiss.
                    modelError.value = "Couldn't change model: ${it.message?.take(120)}"
                },
            )
        }
    }

    fun closeModelPicker() { modelPickerOpen.value = false }

    /** Called from streamChat's onSessionId once the real server session id is known. */
    fun onSessionCaptured(sessionId: String) {
        val pending = pendingOption ?: return
        pendingOption = null
        modelLabel.value = pending.label
        viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (!s.isConfigured) return@launch
            val client = HermesClient(s.baseUrl, s.apiKey)
            client.setSessionModel(sessionId, pending.modelId, pending.providerSlug).fold(
                onSuccess = {
                    modelLabel.value = pending.label
                },
                onFailure = {
                    // The override did not stick server-side; report honestly.
                    modelError.value = "Model couldn't be pinned to this session: ${it.message?.take(120)}"
                    syncLabelWithServer(client)
                },
            )
        }
    }

    /** Ask the server what model this session is actually pinned to (null = default). */
    private suspend fun syncLabelWithServer(client: HermesClient) {
        val sid = repo.sessionId
        if (sid.isNullOrEmpty()) {
            // No session yet → show the server default so the chip is honest.
            modelLabel.value = modelDefault.value?.let { "Automatic · $it" } ?: "Automatic"
            return
        }
        client.getSession(sid).fold(
            onSuccess = { env ->
                val pinned = env.session.model
                if (pinned.isNullOrBlank()) {
                    modelLabel.value = modelDefault.value?.let { "Automatic · $it" } ?: "Automatic"
                } else {
                    modelLabel.value = pinned
                }
            },
            onFailure = { /* keep whatever the chip currently shows; Hermes unreachable */ },
        )
    }

    private fun flattenModels(payload: ModelOptionsPayload): List<ModelOption> {
        val out = mutableListOf<ModelOption>()
        for (row in payload.providers) {
            if (!row.authenticated) continue
            for (modelId in row.models) {
                if (modelId.isBlank()) continue
                val label = if (row.name.isNullOrBlank()) modelId else "${row.name} · $modelId"
                out.add(ModelOption(modelId = modelId, providerSlug = row.slug.ifBlank { null }, label = label))
            }
        }
        return out
    }

    fun newConversation() {
        cancel()
        viewModelScope.launch {
            repo.persist()
            repo.startNew()
        }
    }

    fun cancel() {
        currentSource?.cancel()
        currentSource = null
        isStreaming.value = false
        activity.value = null
    }

    fun dismissNotConfigured() { notConfigured.value = false }

    fun send(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || isStreaming.value) return

        viewModelScope.launch {
            val s = settingsStore.settings.first()
            if (!s.isConfigured) { notConfigured.value = true; return@launch }

            repo.addMessage("user", text)
            val history = repo.historyForRequest()
            val assistantIndex = repo.addMessage("assistant", "")
            isStreaming.value = true
            activity.value = null

            val client = HermesClient(s.baseUrl, s.apiKey)
            currentSource = client.streamChat(history, repo.sessionId, object : HermesClient.StreamCallbacks {
                override fun onDelta(textDelta: String) = onMain {
                    repo.appendToMessage(assistantIndex, textDelta)
                }

                override fun onSessionId(id: String) {
                    repo.setSessionId(id)
                    onSessionCaptured(id)
                }

                override fun onToolProgress(tool: String, label: String?, running: Boolean) = onMain {
                    activity.value = if (running) (label ?: tool) else null
                }

                override fun onComplete() = onMain {
                    isStreaming.value = false
                    currentSource = null
                    activity.value = null
                    viewModelScope.launch { repo.persist() }
                }

                override fun onError(message: String) = onMain {
                    val cur = messages.getOrNull(assistantIndex)
                    if (cur != null && cur.text.isEmpty()) {
                        repo.replaceMessage(assistantIndex, "⚠️ $message", isError = true)
                    } else {
                        repo.addMessage("assistant", "⚠️ $message", isError = true)
                    }
                    isStreaming.value = false
                    currentSource = null
                    activity.value = null
                    viewModelScope.launch { repo.persist() }
                }
            })
        }
    }

    override fun onCleared() {
        cancel()
        repo.persistAsync() // viewModelScope is already cancelled here
        super.onCleared()
    }
}

/** A selectable model from the Hermes catalog inventory. */
data class ModelOption(
    val modelId: String,
    val providerSlug: String? = null,
    val label: String,
)
