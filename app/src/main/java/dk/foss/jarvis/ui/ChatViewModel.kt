package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource
import dk.foss.jarvis.net.E2eLog

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

    // --- transport-aware states ---
    val sendBlocked = mutableStateOf<String?>(null)
    val transportNotice = mutableStateOf<String?>(null)
    val effectiveRoute = mutableStateOf<EffectiveRoute?>(null)

    // --- per-conversation model selection (server-authoritative) ---
    private var modelSelection = ModelSelection()
    private var lastSyncedConversationId: String? = null
    val modelLabel = mutableStateOf("Automatic")
    val modelDefault = mutableStateOf<String?>(null)
    val modelOptions = mutableStateOf<List<ModelOption>>(emptyList())
    val modelPickerOpen = mutableStateOf(false)
    val modelLoading = mutableStateOf(false)
    val modelError = mutableStateOf<String?>(null)

    /** Model requested while no server session existed yet; applied on first session id. */
    var pendingOption: ModelOption? = null

    private val unsubscribeSwitched = repo.onConversationSwitched { pendingOption = null }

    private var currentSource: EventSource? = null
    private var activeTurnJob: kotlinx.coroutines.Job? = null
    private var turnInFlight = false
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Check selectorAvailable: model_options && session_model_lock. */
    val modelSelectorAvailable get() = modelSelection.state.selectorAvailable
    val modelLocked get() = modelSelection.state.locked
    val clearSupported get() = modelSelection.state.clearSupported

    /** Fetch the Hermes catalog and, when a session exists, the current pinned model. */
    fun refreshModel() {
        viewModelScope.launch {
            val conversationId = repo.activeConversationId
            if (conversationId != lastSyncedConversationId) {
                modelSelection.reset()
                effectiveRoute.value = null
                modelLabel.value = "Automatic"
                lastSyncedConversationId = conversationId
            }
            val s = settingsStore.settings.first()
            if (!s.isConfigured) { modelLoading.value = false; return@launch }
            modelLoading.value = true
            val client = HermesClient(s.baseUrl, s.apiKey)
            val gate = repo.verifySessionForCurrentOrigin(client, s.baseUrl, s.apiKey)
            E2eLog.log("refreshModel gate=${gate::class.simpleName}")
            when (gate) {
                is ConversationRepository.RebindOutcome.BlockedAuth,
                is ConversationRepository.RebindOutcome.BlockedMissing,
                is ConversationRepository.RebindOutcome.BlockedRetryable -> {
                    modelLoading.value = false
                    modelError.value = "Session verification failed; try again."
                    return@launch
                }
                else -> Unit
            }
            val origin = originIdentity(s.baseUrl, s.apiKey)
            val caps = CapabilityRegistry.capabilities(origin) { client.getCapabilities() }

            if (caps.features.model_options && caps.features.session_model_lock) {
                modelSelection.onSelectorAvailability(
                    modelOptions = true,
                    lock = caps.features.session_model_lock,
                    clear = caps.features.session_model_clear,
                )
            } else {
                modelSelection.onSelectorAvailability(
                    modelOptions = caps.features.model_options,
                    lock = caps.features.session_model_lock,
                    clear = caps.features.session_model_clear,
                )
            }
            E2eLog.log("refreshModel capsState=${caps.state} mo=${caps.features.model_options} lock=${caps.features.session_model_lock} available=${modelSelection.state.selectorAvailable}")

            client.getModelOptions().fold(
                onSuccess = {
                    modelOptions.value = flattenModels(it)
                    modelDefault.value = it.model
                    modelLoading.value = false
                     syncLabelWithServer(client, s.baseUrl, s.apiKey)
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
            E2eLog.log("chooseModel activeId=${repo.activeConversationId} sid=$sid option=${option?.modelId}")
            val s = settingsStore.settings.first()
            if (!s.isConfigured) { modelError.value = "Configure Hermes in Settings first"; return@launch }

            // Reflect the choice optimistically; the server confirms below.
            val chosenLabel = option?.label ?: "Automatic"
            modelLabel.value = chosenLabel
            modelPickerOpen.value = false

            if (sid.isNullOrEmpty()) {
                // No server session yet — remember the intent.
                // Incondicional: elegir Automatic (option == null) LIMPIA la pendiente,
                // y elegir un modelo específico la establece.
                pendingOption = option
                return@launch
            }

            val client = HermesClient(s.baseUrl, s.apiKey)
            val gate = repo.verifySessionForCurrentOrigin(client, s.baseUrl, s.apiKey)
            E2eLog.log("chooseModel gate=${gate::class.simpleName}")
            when (gate) {
                is ConversationRepository.RebindOutcome.BlockedAuth -> { modelError.value = "Authentication failed while verifying this session."; return@launch }
                is ConversationRepository.RebindOutcome.BlockedMissing -> { modelError.value = "This session no longer exists."; return@launch }
                is ConversationRepository.RebindOutcome.BlockedRetryable -> { modelError.value = "Couldn't verify this session; try again."; return@launch }
                else -> Unit
            }

            // Check selector availability first
            if (!modelSelection.state.selectorAvailable) {
                modelError.value = "Model selection is not supported by this server."
                modelLabel.value = modelSelection.state.label
                return@launch
            }

            val result = if (option == null) client.clearSessionModel(sid)
            else client.setSessionModel(sid, option.modelId)

            result.fold(
                onSuccess = {
                    if (option == null) {
                        // Clear
                        modelSelection.onClearAck(it.runtime)
                        modelLabel.value = "Automatic"
                    } else {
                        // Set
                        modelSelection.onSetAck(chosenLabel, it.runtime)
                        modelLabel.value = chosenLabel
                    }
                    effectiveRoute.value = EffectiveRoute.fromRuntime(it.runtime)
                    modelPickerOpen.value = false
                },
                onFailure = {
                    modelSelection.onRejected()
                    modelLabel.value = modelSelection.state.label
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
            client.setSessionModel(sessionId, pending.modelId).fold(
                onSuccess = {
                    modelLabel.value = pending.label
                    effectiveRoute.value = EffectiveRoute.fromRuntime(it.runtime)
                },
                onFailure = {
                    modelError.value = "Model couldn't be pinned to this session: ${it.message?.take(120)}"
                     syncLabelWithServer(client, s.baseUrl, s.apiKey)
                },
            )
        }
    }

    /** Ask the server what model this session is actually pinned to (null = default). */
    private suspend fun syncLabelWithServer(client: HermesClient, baseUrl: String, apiKey: String) {
        val sid = repo.sessionId
        E2eLog.log("syncLabel sid=${sid ?: "null"}")
        if (sid.isNullOrEmpty()) {
            modelSelection.reset()
            effectiveRoute.value = null
            modelLabel.value = "Automatic"
            return
        }
        val gate = repo.verifySessionForCurrentOrigin(client, baseUrl, apiKey)
        E2eLog.log("syncLabel gate=${gate::class.simpleName}")
        when (gate) {
            is ConversationRepository.RebindOutcome.BlockedAuth,
            is ConversationRepository.RebindOutcome.BlockedMissing,
            is ConversationRepository.RebindOutcome.BlockedRetryable -> return
            else -> Unit
        }
        client.getSession(sid).fold(
            onSuccess = { env ->
                modelSelection.onSessionInsight(env.session.model, modelDefault.value)
                modelLabel.value = modelSelection.state.label
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
                out.add(ModelOption(modelId = modelId, label = label))
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
        modelSelection.reset()
        modelLabel.value = "Automatic"
        effectiveRoute.value = null
        sendBlocked.value = null
        transportNotice.value = null
        pendingOption = null
    }

    fun cancel() {
        currentSource?.cancel()
        currentSource = null
        activeTurnJob?.cancel()
        activeTurnJob = null
        isStreaming.value = false
        activity.value = null
    }

    fun dismissNotConfigured() { notConfigured.value = false }

    /** Public entry point: routes through the transport-aware decision engine. */
    fun sendUserMessage(userText: String) {
        val text = userText.trim()
        if (text.isEmpty() || isStreaming.value || turnInFlight) return

        turnInFlight = true
        activeTurnJob = viewModelScope.launch {
            try {
                val s = settingsStore.settings.first()
                if (!s.isConfigured) { notConfigured.value = true; return@launch }

                val client = HermesClient(s.baseUrl, s.apiKey)
                val hasMessages = repo.messages.any { !it.isError }
                E2eLog.log("send activeId=${repo.activeConversationId} sid=${repo.sessionId} hasMessages=$hasMessages")

                when (val plan = resolveContinuation(repo, client, s.baseUrl, s.apiKey, hasMessages)) {
                    is ContinuationPlan.Blocked -> {
                        when (plan.outcome) {
                            is ConversationRepository.RebindOutcome.BlockedAuth -> appendSystemError("Authentication failed while verifying this session.")
                            is ConversationRepository.RebindOutcome.BlockedMissing -> appendSystemError("This session no longer exists (session_not_found).")
                            is ConversationRepository.RebindOutcome.BlockedRetryable -> appendSystemError("Couldn't verify this session; try again.")
                            else -> Unit
                        }
                        return@launch
                    }
                    is ContinuationPlan.Send -> when (val d = plan.decision) {
                    is ChatTransportDecision.Blocked -> {
                        appendSystemError(d.reason)
                        return@launch
                    }
                    is ChatTransportDecision.Unavailable -> {
                        sendBlocked.value = d.reason
                        appendSystemError(d.reason)
                        return@launch
                    }
                    is ChatTransportDecision.Legacy -> sendLegacy(client, text)
                    is ChatTransportDecision.Sessions -> sendSessions(client, d.features, text)
                    }
                }
            } finally {
                turnInFlight = false
            }
        }
    }

    // Keep the old send() for backward compatibility (it delegates to sendUserMessage)
    @Deprecated("Use sendUserMessage", replaceWith = ReplaceWith("sendUserMessage(text)"))
    fun send(userText: String) = sendUserMessage(userText)

    private suspend fun sendLegacy(client: HermesClient, userText: String) {
        repo.addMessage("user", userText)
        val history = repo.historyForRequest()
        val assistantIndex = repo.addMessage("assistant", "")
        isStreaming.value = true
        activity.value = null

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
                viewModelScope.launch {
                    if (repo.transport == null) repo.markTransport(ChatTransportKind.LEGACY_CHAT)
                    repo.markUsed()
                    repo.persist()
                }
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

    private suspend fun sendSessions(client: HermesClient, features: ServerFeatures, userText: String) {
        val s = settingsStore.settings.first()
        val origin = originIdentity(s.baseUrl, s.apiKey)
        if (repo.transport != ChatTransportKind.SESSIONS) repo.bindTransport(origin, ChatTransportKind.SESSIONS)
        val queuedText = repo.queueFirstTurn(userText)
        val pending = pendingOption
        when (val outcome = startSessionTurn(
            repo, client, origin, sessionTitleFrom(queuedText), repo.activeConversationId,
            pending?.modelId,
        )) {
            is SessionTurnStartOutcome.CreateFailed -> {
                appendSystemError("Couldn't start a Hermes session: ${outcome.error.message?.take(120) ?: "unknown"}")
                viewModelScope.launch { repo.persist() }
                return
            }
            is SessionTurnStartOutcome.LockFailed -> {
                modelSelection.onRejected()
                modelLabel.value = modelSelection.state.label
                effectiveRoute.value = null
                modelError.value = "Couldn't pin model to session: ${outcome.error.message?.take(120)}"
                repo.persistAsync()
                return
            }
            is SessionTurnStartOutcome.Started -> if (pending != null) {
                // Consume only after the lock ACK; a retry must issue the lock again.
                pendingOption = null
                modelSelection.onSetAck(pending.label, outcome.runtime)
                modelLabel.value = pending.label
                outcome.runtime?.let { effectiveRoute.value = EffectiveRoute.fromRuntime(it) }
            }
        }

        val assistantIndex = repo.addMessage("assistant", "")
        isStreaming.value = true
        activity.value = null

        val sid = repo.sessionId ?: return

        if (features.session_chat_streaming) {
            sendSessionStreaming(client, sid, userText, assistantIndex)
        } else {
            sendSessionTurnNonStreaming(client, sid, userText, assistantIndex)
        }
    }

    private fun sendSessionStreaming(client: HermesClient, sid: String, userText: String, assistantIndex: Int) {
        currentSource = client.streamSessionTurn(sid, userText, object : HermesClient.StreamCallbacks {
            override fun onDelta(textDelta: String) = onMain {
                repo.appendToMessage(assistantIndex, textDelta)
            }

            override fun onFinalContent(text: String) = onMain {
                // Idempotent replacement — ensures no duplication
                repo.replaceMessage(assistantIndex, text)
            }

            override fun onToolProgress(tool: String, label: String?, running: Boolean) = onMain {
                activity.value = if (running) (label ?: tool) else null
            }

            override fun onRuntime(info: RuntimeInfo) = onMain {
                effectiveRoute.value = EffectiveRoute.fromRuntime(info)
                if (info.model_lock == "accepted") {
                    modelSelection.onSetAck(info.model ?: "Automatic", info)
                    modelLabel.value = modelSelection.state.label
                }
            }

            override fun onComplete() = onMain {
                isStreaming.value = false
                currentSource = null
                activity.value = null
                viewModelScope.launch {
                    repo.markUsed()
                    repo.persist()
                }
            }

            override fun onError(message: String) = onMain {
                val cleanMsg = message.replace("^HTTP \\d+: ?".toRegex(), "")
                if (message.contains("401") || message.contains("403") || message.contains("gateway_auth_failed")) {
                    val errMsg = "Authentication failed (${cleanMsg.take(40)}). Check the API key in Settings."
                    val cur = messages.getOrNull(assistantIndex)
                    if (cur != null && cur.text.isEmpty()) {
                        repo.replaceMessage(assistantIndex, "⚠️ $errMsg", isError = true)
                    } else {
                        repo.addMessage("assistant", "⚠️ $errMsg", isError = true)
                    }
                } else if (message.contains("404") || message.contains("session_not_found") || message.contains("no longer exists")) {
                    val errMsg = "Remote session no longer exists (session_not_found). Start a new conversation from History to continue."
                    sendBlocked.value = errMsg
                    val cur = messages.getOrNull(assistantIndex)
                    if (cur != null && cur.text.isEmpty()) {
                        repo.replaceMessage(assistantIndex, "⚠️ $errMsg", isError = true)
                    } else {
                        repo.addMessage("assistant", "⚠️ $errMsg", isError = true)
                    }
                } else {
                    val cur = messages.getOrNull(assistantIndex)
                    if (cur != null && cur.text.isEmpty()) {
                        repo.replaceMessage(assistantIndex, "⚠️ $cleanMsg", isError = true)
                    } else {
                        repo.addMessage("assistant", "⚠️ $cleanMsg", isError = true)
                    }
                }
                isStreaming.value = false
                currentSource = null
                activity.value = null
                viewModelScope.launch { repo.persist() }
            }
        })
    }

    private suspend fun sendSessionTurnNonStreaming(client: HermesClient, sid: String, userText: String, assistantIndex: Int) {
        val result = client.sendSessionTurn(sid, userText)
        onMain {
            result.fold(
                onSuccess = { turnResult ->
                    val text = turnResult.text ?: "⚠️ No response content"
                    if (text.startsWith("⚠️")) {
                        repo.replaceMessage(assistantIndex, text, isError = true)
                    } else {
                        repo.replaceMessage(assistantIndex, text)
                    }
                    effectiveRoute.value = EffectiveRoute.fromRuntime(turnResult.runtime)
                    isStreaming.value = false
                    activity.value = null
                    viewModelScope.launch {
                        repo.markUsed()
                        repo.persist()
                    }
                },
                onFailure = {
                    val msg = it.message?.take(120) ?: "Session turn failed"
                    val hermesErr = it as? HermesHttpError
                    if (hermesErr?.isSessionMissing == true) {
                        val errMsg = "Remote session no longer exists (session_not_found). Start a new conversation from History to continue."
                        sendBlocked.value = errMsg
                        repo.replaceMessage(assistantIndex, "⚠️ $errMsg", isError = true)
                    } else if (hermesErr?.isAuth == true) {
                        val errMsg = "Authentication failed (401/403). Check the API key in Settings."
                        repo.replaceMessage(assistantIndex, "⚠️ $errMsg", isError = true)
                    } else {
                        repo.replaceMessage(assistantIndex, "⚠️ $msg", isError = true)
                    }
                    isStreaming.value = false
                    activity.value = null
                    viewModelScope.launch { repo.persist() }
                }
            )
        }
    }

    private fun appendSystemError(msg: String) {
        repo.addMessage("assistant", "⚠️ $msg", isError = true)
    }

    override fun onCleared() {
        cancel()
        unsubscribeSwitched()
        uiScope.cancel()
        repo.persistAsync()
        super.onCleared()
    }
}

/** A selectable model from the Hermes catalog inventory. */
data class ModelOption(
    val modelId: String,
    val label: String,
)
