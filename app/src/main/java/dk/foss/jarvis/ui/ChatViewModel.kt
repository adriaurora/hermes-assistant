package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.PendingModelIntent
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private var currentSource: EventSource? = null
    private var activeTurnJob: kotlinx.coroutines.Job? = null
    private var turnInFlight = false
    private var streamGeneration = 0
    /** Model changes share one ordering domain for text and voice. */
    private val modelCoordinator = ModelOperationCoordinator()
    private var transitionInFlight = false
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val removeConversationListener = repo.onConversationSwitched {
        cancel()
        modelSelection.reset()
        modelLabel.value = pendingModelLabel(repo.pendingModelIntent) ?: "Automatic"
        effectiveRoute.value = null
        sendBlocked.value = null
    }

    init {
        // Restore the app-scoped active conversation after process recreation.
        viewModelScope.launch { repo.restoreLatest() }
    }

    /** Check selectorAvailable: model_options && session_model_lock. */
    val modelSelectorAvailable get() = modelSelection.state.selectorAvailable
    val modelLocked get() = modelSelection.state.locked
    val clearSupported get() = modelSelection.state.clearSupported

    /** Fetch the Hermes catalog and, when a session exists, the current pinned model. */
    fun refreshModel() {
        val conversationId = repo.activeConversationId
        val sessionId = repo.sessionId
        val operation = modelCoordinator.next(conversationId, null, sessionId)
        viewModelScope.launch {
            modelCoordinator.run(operation, { modelOperationCurrent(operation) }) {
            if (!!modelOperationCurrent(operation)) return@launch
            if (conversationId != lastSyncedConversationId) {
                modelSelection.reset()
                effectiveRoute.value = null
                modelLabel.value = "Automatic"
                lastSyncedConversationId = conversationId
            }
            val s = settingsStore.settings.first()
            if (!!modelOperationCurrent(operation)) return@launch
            if (!s.isConfigured) { modelLoading.value = false; return@launch }
            modelLoading.value = true
            val client = HermesClient(s.baseUrl, s.apiKey)
            val gate = repo.verifySessionForCurrentOrigin(client, s.baseUrl, s.apiKey)
            if (!!modelOperationCurrent(operation)) return@launch
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
            if (!!modelOperationCurrent(operation)) return@launch

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
                    if (!modelOperationCurrent(operation)) return@fold
                    modelOptions.value = flattenModels(it)
                    modelDefault.value = it.model
                    modelLoading.value = false
                    syncLabelWithServer(client, s.baseUrl, s.apiKey, operation, conversationId, sessionId)
                },
                onFailure = {
                    modelLoading.value = false
                    modelError.value = "Models are unavailable. Try again."
                },
            )
            }
        }
    }

    /** Choose [option], or pass null for Automatic (clear the session override). */
    fun chooseModel(option: ModelOption?) {
        val conversationId = repo.activeConversationId
        val sessionId = repo.sessionId
        val operation = modelCoordinator.next(conversationId, null, sessionId)
        viewModelScope.launch {
            modelCoordinator.run(operation, { modelOperationCurrent(operation) }) {
            if (!!modelOperationCurrent(operation)) return@launch
            modelError.value = null
            val sid = sessionId
            E2eLog.log("chooseModel activeId=${conversationId} sid=$sid option=${option?.modelId}")
            val s = settingsStore.settings.first()
            if (!!modelOperationCurrent(operation)) return@launch
            if (!s.isConfigured) { modelError.value = "Configure Hermes in Settings first"; return@launch }

            // Gates must not leave a stale intent behind. A real choice is queued
            // before verification (so a blocked verification can be retried).
            val chosenLabel = option?.label ?: "Automatic"
            val intent = option?.let { PendingModelIntent.Set(it.modelId, it.label) } ?: PendingModelIntent.Clear
            val previousIntent = repo.pendingModelIntent
            repo.recordPendingModelIntent(intent)
            if (!modelOperationCurrent(operation) || repo.pendingModelIntent != intent) return@launch
            modelLabel.value = chosenLabel
            modelPickerOpen.value = false

            val client = HermesClient(s.baseUrl, s.apiKey)
            if (!sid.isNullOrEmpty()) {
                val gate = repo.verifySessionForCurrentOrigin(client, s.baseUrl, s.apiKey)
                if (!modelOperationCurrent(operation) || repo.pendingModelIntent != intent) return@launch
                E2eLog.log("chooseModel gate=${gate::class.simpleName}")
                when (gate) {
                    is ConversationRepository.RebindOutcome.BlockedAuth -> { modelLabel.value = modelSelection.state.label; modelError.value = "Authentication failed while verifying this session."; return@launch }
                    is ConversationRepository.RebindOutcome.BlockedMissing -> { modelLabel.value = modelSelection.state.label; modelError.value = "This session no longer exists."; return@launch }
                    is ConversationRepository.RebindOutcome.BlockedRetryable -> { modelLabel.value = modelSelection.state.label; modelError.value = "Couldn't verify this session; try again."; return@launch }
                    else -> Unit
                }
            }

            // Check selector availability first
            if (!modelSelection.state.selectorAvailable) {
                repo.recordPendingModelIntent(previousIntent)
                modelError.value = "Model selection is not supported by this server."
                modelLabel.value = modelSelection.state.label
                return@launch
            }

            // No server session yet — the repository remembers the intent.
            if (sid.isNullOrEmpty()) return@launch

            val result = if (option == null) client.clearSessionModel(sid)
            else client.setSessionModel(sid, option.modelId)
            if (!modelOperationCurrent(operation) || repo.pendingModelIntent != intent) return@launch

            result.fold(
                onSuccess = {
                    if (!modelOperationCurrent(operation) || repo.pendingModelIntent != intent) return@fold
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
                    repo.consumePendingModelIntentDurably(intent, conversationId)
                    modelPickerOpen.value = false
                },
                onFailure = {
                    modelSelection.onRejected()
                    modelLabel.value = modelSelection.state.label
                    modelError.value = "Couldn't change the model. Try again."
                },
            )
            }
        }
    }

    fun closeModelPicker() { modelPickerOpen.value = false }

    /** Called when a session is created and bound to the active conversation. */
    fun onSessionCaptured(sessionId: String) {
        val conversationId = repo.activeConversationId
        val pending = repo.pendingModelIntent ?: return
        val operation = modelCoordinator.next(conversationId, null, repo.sessionId)
        if (pending is PendingModelIntent.Set) modelLabel.value = pending.label
        viewModelScope.launch {
            modelCoordinator.run(operation, { modelOperationCurrent(operation) }) {
            if (repo.activeConversationId != conversationId || repo.pendingModelIntent != pending ||
                !modelOperationCurrent(operation)) return@launch
            val s = settingsStore.settings.first()
            if (!s.isConfigured) return@launch
            val client = HermesClient(s.baseUrl, s.apiKey)
            val result = when (pending) {
                is PendingModelIntent.Set -> client.setSessionModel(sessionId, pending.modelId)
                PendingModelIntent.Clear -> client.clearSessionModel(sessionId)
            }
            result.fold(
                onSuccess = {
                    if (repo.activeConversationId != conversationId || repo.pendingModelIntent != pending ||
                        !modelOperationCurrent(operation) || repo.sessionId != sessionId) return@fold
                    when (pending) {
                        is PendingModelIntent.Set -> {
                            modelLabel.value = pending.label
                            modelSelection.onSetAck(pending.label, it.runtime)
                        }
                        PendingModelIntent.Clear -> {
                            modelLabel.value = "Automatic"
                            modelSelection.onClearAck(it.runtime)
                        }
                    }
                     repo.consumePendingModelIntentDurably(pending, conversationId)
                    effectiveRoute.value = EffectiveRoute.fromRuntime(it.runtime)
                },
                onFailure = {
                    modelError.value = "Couldn't pin the model to this session. Try again."
                     syncLabelWithServer(client, s.baseUrl, s.apiKey, operation, conversationId, sessionId)
                },
            )
            }
        }
    }

    /** Ask the server what model this session is actually pinned to (null = default). */
    private suspend fun syncLabelWithServer(client: HermesClient, baseUrl: String, apiKey: String,
                                             operation: ModelOperationCoordinator.Context, conversationId: String, sessionId: String?) {
        if (!modelOperationCurrent(operation)) return
        val sid = sessionId
        E2eLog.log("syncLabel sid=${sid ?: "null"}")
        if (sid.isNullOrEmpty()) {
            modelSelection.reset()
            effectiveRoute.value = null
            modelLabel.value = pendingModelLabel(repo.pendingModelIntent) ?: "Automatic"
            return
        }
        val gate = repo.verifySessionForCurrentOrigin(client, baseUrl, apiKey)
        if (!modelOperationCurrent(operation)) return
        E2eLog.log("syncLabel gate=${gate::class.simpleName}")
        when (gate) {
            is ConversationRepository.RebindOutcome.BlockedAuth,
            is ConversationRepository.RebindOutcome.BlockedMissing,
            is ConversationRepository.RebindOutcome.BlockedRetryable -> return
            else -> Unit
        }
        client.getSession(sid).fold(
            onSuccess = { env ->
                if (!modelOperationCurrent(operation)) return@fold
                modelSelection.onSessionInsight(env.session.model, modelDefault.value)
                modelLabel.value = modelSelection.state.label
            },
            onFailure = { /* keep whatever the chip currently shows; Hermes unreachable */ },
        )
    }

    private fun modelOperationCurrent(operation: ModelOperationCoordinator.Context): Boolean =
        operation.conversationId == repo.activeConversationId && operation.sessionId == repo.sessionId &&
            modelCoordinator.isCurrent(operation) { true }

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
        if (transitionInFlight) return
        transitionInFlight = true
        cancel()
        viewModelScope.launch {
            try {
                repo.startNewAtomically()
                modelSelection.reset()
                modelLabel.value = "Automatic"
                effectiveRoute.value = null
                sendBlocked.value = null
                transportNotice.value = null
            } finally {
                transitionInFlight = false
            }
        }
    }

    fun cancel() {
        streamGeneration++
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
        if (text.isEmpty() || isStreaming.value || turnInFlight || transitionInFlight) return

        turnInFlight = true
        activeTurnJob = viewModelScope.launch {
            try {
                val s = settingsStore.settings.first()
                if (!s.isConfigured) { notConfigured.value = true; return@launch }

                val client = HermesClient(s.baseUrl, s.apiKey)
                E2eLog.log("send activeId=${repo.activeConversationId} sid=${repo.sessionId}")

                when (val plan = resolveContinuation(repo, client, s.baseUrl, s.apiKey)) {
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

    private suspend fun sendSessions(client: HermesClient, features: ServerFeatures, userText: String) {
        val s = settingsStore.settings.first()
        val origin = originIdentity(s.baseUrl, s.apiKey)
        if (repo.transport != ChatTransportKind.SESSIONS) repo.bindTransport(origin, ChatTransportKind.SESSIONS)
        val queuedText = repo.queueFirstTurn(userText)
        val pending = repo.pendingModelIntent
        when (val outcome = startSessionTurn(
            repo, client, origin, sessionTitleFrom(queuedText), repo.activeConversationId,
            pending,
        )) {
            is SessionTurnStartOutcome.CreateFailed -> {
                appendSystemError("Couldn't start a Hermes session. Try again.")
                viewModelScope.launch { repo.persist() }
                return
            }
            is SessionTurnStartOutcome.LockFailed -> {
                modelSelection.onRejected()
                modelLabel.value = modelSelection.state.label
                effectiveRoute.value = null
                modelError.value = "Couldn't pin the model to this session. Try again."
                repo.persistAsync()
                return
            }
            is SessionTurnStartOutcome.Started -> if (pending != null) {
                when (pending) {
                    is PendingModelIntent.Set -> {
                        modelSelection.onSetAck(pending.label, outcome.runtime)
                        modelLabel.value = pending.label
                    }
                    PendingModelIntent.Clear -> {
                        modelSelection.onClearAck(outcome.runtime)
                        modelLabel.value = "Automatic"
                    }
                }
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
        val generation = streamGeneration
        val expectedTranscript = repo.historyForRequest().dropLast(1)
        currentSource = client.streamSessionTurn(sid, userText, object : HermesClient.StreamCallbacks {
            override fun onDelta(textDelta: String) = onMain {
                if (generation != streamGeneration) return@onMain
                repo.appendToMessage(assistantIndex, textDelta)
            }

            override fun onFinalContent(text: String) = onMain {
                if (generation != streamGeneration) return@onMain
                // Idempotent replacement — ensures no duplication
                repo.replaceMessage(assistantIndex, text)
            }

            override fun onToolProgress(tool: String, label: String?, running: Boolean) = onMain {
                if (generation != streamGeneration) return@onMain
                activity.value = if (running) (label ?: tool) else null
            }

            override fun onRuntime(info: RuntimeInfo) = onMain {
                if (generation != streamGeneration) return@onMain
                effectiveRoute.value = EffectiveRoute.fromRuntime(info)
                if (info.model_lock == "accepted") {
                    modelSelection.onSetAck(info.model ?: "Automatic", info)
                    modelLabel.value = modelSelection.state.label
                }
            }

            override fun onComplete() = onMain {
                if (generation != streamGeneration) return@onMain
                isStreaming.value = false
                currentSource = null
                activity.value = null
                viewModelScope.launch {
                    repo.markUsed()
                    repo.persist()
                }
            }

            override fun onError(streamError: Throwable) = onMain {
                if (generation != streamGeneration) return@onMain
                if (shouldReconcile(streamError)) {
                    // A broken SSE connection is ambiguous: the server may have
                    // committed the turn. Read authoritative history instead of
                    // ever replaying the user's message.
                    isStreaming.value = true
                    viewModelScope.launch {
                        reconcileStream(client, sid, assistantIndex, generation, streamError, expectedTranscript)
                    }
                    return@onMain
                }
                val errMsg = semanticChatError(streamError)
                if (errMsg.isBlank()) return@onMain
                if ((streamError as? HermesHttpError)?.isAuth == true) {
                    val cur = messages.getOrNull(assistantIndex)
                    if (cur != null && cur.text.isEmpty()) {
                        repo.replaceMessage(assistantIndex, "⚠️ $errMsg", isError = true)
                    } else {
                        repo.addMessage("assistant", "⚠️ $errMsg", isError = true)
                    }
                } else if ((streamError as? HermesHttpError)?.isSessionMissing == true) {
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
                        repo.replaceMessage(assistantIndex, "⚠️ $errMsg", isError = true)
                    } else {
                        repo.addMessage("assistant", "⚠️ $errMsg", isError = true)
                    }
                }
                isStreaming.value = false
                currentSource = null
                activity.value = null
                viewModelScope.launch { repo.persist() }
            }
        })
    }

    private fun shouldReconcile(error: Throwable): Boolean {
        val http = error as? HermesHttpError
        return error is StreamClosedBeforeTerminalError ||
            (http == null && error !is java.util.concurrent.CancellationException) ||
            (http?.code ?: 0) in 500..599
    }

    private suspend fun reconcileStream(
        client: HermesClient,
        sid: String,
        assistantIndex: Int,
        generation: Int,
        originalError: Throwable,
        expectedTranscript: List<ChatMessage>,
    ) {
        val result = client.getSessionMessages(sid, limit = 500)
        onMain {
            if (generation != streamGeneration) return@onMain
            val authoritative = result.getOrNull()?.data.orEmpty()
            val answer = reconciledAnswer(expectedTranscript, authoritative.map { ChatMessage(it.role, it.content) })
            if (answer != null) {
                // Replace the local bubble, including partial deltas, so retries
                // and reconnect callbacks cannot duplicate visible content.
                repo.replaceMessage(assistantIndex, answer)
                isStreaming.value = false
                currentSource = null
                activity.value = null
                viewModelScope.launch { repo.markUsed(); repo.persist() }
            } else {
                val msg = semanticChatError(originalError)
                val cur = messages.getOrNull(assistantIndex)
                if (cur != null && cur.text.isEmpty()) repo.replaceMessage(assistantIndex, "⚠️ $msg", isError = true)
                else repo.addMessage("assistant", "⚠️ $msg", isError = true)
                isStreaming.value = false
                currentSource = null
                activity.value = null
                viewModelScope.launch { repo.persist() }
            }
        }
    }

    private suspend fun sendSessionTurnNonStreaming(client: HermesClient, sid: String, userText: String, assistantIndex: Int) {
        val generation = streamGeneration
        val result = client.sendSessionTurn(sid, userText)
        onMain {
            if (generation != streamGeneration) return@onMain
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
                    val hermesErr = it as? HermesHttpError
                    if (hermesErr?.isSessionMissing == true) {
                        val errMsg = "Remote session no longer exists (session_not_found). Start a new conversation from History to continue."
                        sendBlocked.value = errMsg
                        repo.replaceMessage(assistantIndex, "⚠️ $errMsg", isError = true)
                    } else if (hermesErr?.isAuth == true) {
                        val errMsg = semanticChatError(it)
                        repo.replaceMessage(assistantIndex, "⚠️ $errMsg", isError = true)
                    } else {
                        repo.replaceMessage(assistantIndex, "⚠️ ${semanticChatError(it)}", isError = true)
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
        removeConversationListener()
        cancel()
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
