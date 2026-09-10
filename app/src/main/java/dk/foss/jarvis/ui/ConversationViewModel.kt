package dk.foss.jarvis.ui

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.JarvisSettings
import dk.foss.jarvis.data.SettingsStore
import dk.foss.jarvis.hermes.*
import dk.foss.jarvis.voice.AndroidTts
import dk.foss.jarvis.voice.SpeechInput
import dk.foss.jarvis.voice.TtsEngine
import dk.foss.jarvis.voice.VoiceRecognizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.sse.EventSource

enum class ConvState { Idle, Listening, Thinking, Speaking }

class ConversationViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsStore = SettingsStore(app)
    private val repo = ConversationRepository.get(app)
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: VoiceRecognizer? = null

    /** Run on the main thread; returns Unit so it fits expression-body callbacks. */
    private fun onMain(block: () -> Unit) { main.post(block) }

    val state = mutableStateOf(ConvState.Idle)
    val transcript = mutableStateOf("")
    val reply = mutableStateOf("")
    val error = mutableStateOf<String?>(null)
    val hint = mutableStateOf<String?>(null)
    val working = mutableStateOf(false) // Hermes stream still open (response not complete)
    val stalled = mutableStateOf(false) // content paused mid-stream — likely running a tool
    val toolLabel = mutableStateOf<String?>(null) // real label from hermes.tool.progress frames

    // --- follow-along reply display state ---
    val segments = androidx.compose.runtime.mutableStateListOf<String>()
    val speakingIndex = mutableStateOf(-1)
    val pendingText = mutableStateOf("")
    private var spokenCount = 0

    private var settings: JarvisSettings? = null
    private var tts: TtsEngine? = null
    private var source: EventSource? = null
    private var continuous = true

    // --- streaming-TTS pipeline ---
    private val sentenceBuffer = StringBuilder()
    private val ttsQueue = ArrayDeque<String>()
    private var speaking = false
    private var streamDone = false
    private var turn = 0 // bumped each turn; stale async callbacks check this and bail
    private var retriedThisTurn = false

    // Speak a complete sentence that's been sitting in the buffer once the stream
    // goes quiet (e.g. the agent paused to run a tool), not only when more text arrives.
    private val idleFlush = Runnable { flushPendingSentence() }

    // If content pauses while the stream is still open, the agent is likely running a tool.
    private val stallIndicator = Runnable { if (working.value) stalled.value = true }

    init {
        viewModelScope.launch { ensureReady() }
    }

    private suspend fun ensureReady() {
        val s = settings ?: settingsStore.settings.first().also { settings = it }
        if (tts == null) tts = AndroidTts(getApplication(), languageTag = null)
        if (recognizer == null) {
            recognizer = SpeechInput(getApplication())
            recognizer?.prewarm()
        }
    }

    fun startListening() {
        // A conversation auto-continues: after Hermes speaks it listens again.
        continuous = true
        retriedThisTurn = false
        viewModelScope.launch {
            ensureReady()
            if (settings?.isConfigured != true) {
                error.value = "Configure Hermes in Settings first."
                state.value = ConvState.Idle
                return@launch
            }
            beginTurn()
            state.value = ConvState.Listening
            startRecognition()
        }
    }

    /** Start a fresh turn: invalidate in-flight callbacks and clear pipeline state. */
    private fun beginTurn() {
        turn++
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        working.value = false
        stalled.value = false
        toolLabel.value = null
        // Stop any in-flight recognition so the next start isn't blocked by a
        // still-bound recognizer.
        runCatching { recognizer?.stop() }
        source?.cancel(); source = null
        runCatching { tts?.stop() }
        ttsQueue.clear()
        sentenceBuffer.setLength(0)
        speaking = false
        streamDone = false
        segments.clear()
        speakingIndex.value = -1
        pendingText.value = ""
        spokenCount = 0
        transcript.value = ""
        reply.value = ""
        error.value = null
        hint.value = null
    }

    /**
     * Reset the on-screen display when the voice screen is (re)opened, so it reflects
     * the current conversation rather than a stale previous exchange (the VM is retained
     * across screen visits while the shared conversation may have been replaced).
     */
    fun resetView() {
        turn++ // invalidate any in-flight callbacks from a prior screen visit
        runCatching { recognizer?.stop() }
        source?.cancel(); source = null
        transcript.value = ""
        reply.value = ""
        error.value = null
        hint.value = null
        working.value = false
        stalled.value = false
        toolLabel.value = null
        segments.clear()
        speakingIndex.value = -1
        pendingText.value = ""
        spokenCount = 0
        state.value = ConvState.Idle
    }

    private fun startRecognition() {
        val myTurn = turn
        recognizer?.start(languageTag = null, listener = object : VoiceRecognizer.Listener {
            override fun onPartial(text: String) = onMain {
                if (turn == myTurn) transcript.value = text
            }

            override fun onEnd() = onMain {
                // Recording finished — show "Thinking" while Scribe transcribes.
                if (turn == myTurn && state.value == ConvState.Listening) state.value = ConvState.Thinking
            }

            override fun onFinal(text: String) = onMain {
                if (turn != myTurn) return@onMain
                transcript.value = text
                if (text.isBlank()) goIdle() else think(text)
            }

            override fun onError(message: String, transient: Boolean) = onMain {
                if (turn != myTurn) return@onMain
                // Cold-start / mic-handoff hiccup right after a wake — retry once.
                if (transient && !retriedThisTurn) {
                    retriedThisTurn = true
                    main.postDelayed({ if (turn == myTurn) startRecognition() }, 450)
                    return@onMain
                }
                hint.value = message
                goIdle()
            }
        })
    }

    private fun think(userText: String) {
        val myTurn = turn
        hint.value = null
        state.value = ConvState.Thinking
        working.value = true
        main.postDelayed(stallIndicator, STALL_MS)
        repo.addMessage("user", userText)

        val s = settings ?: return
        val client = HermesClient(s.baseUrl, s.apiKey)
        val origin = originIdentity(s.baseUrl)
        val hasMessages = repo.messages.any { !it.isError }

        // Resolve caps in a coroutine to decide transport
        viewModelScope.launch {
            val caps = CapabilityRegistry.capabilities(origin) { client.getCapabilities() }

            when (val d = ChatTransportSelector.decide(caps, repo.transport, repo.origin, origin, hasMessages)) {
                is ChatTransportDecision.Blocked -> {
                    onMain {
                        error.value = d.reason
                        goIdle()
                    }
                    return@launch
                }
                is ChatTransportDecision.Unavailable -> {
                    onMain {
                        error.value = d.reason
                        goIdle()
                    }
                    return@launch
                }
                is ChatTransportDecision.Legacy -> sendLegacy(client, myTurn)
                is ChatTransportDecision.Sessions -> sendSessions(client, d.features, myTurn, s.baseUrl)
            }
        }
    }

    private fun sendLegacy(client: HermesClient, myTurn: Int) {
        val requestHistory = repo.historyForRequest()

        source = client.streamChat(requestHistory, repo.sessionId, object : HermesClient.StreamCallbacks {
            override fun onDelta(textDelta: String) = onMain {
                if (turn == myTurn) onTextDelta(textDelta)
            }

            override fun onSessionId(id: String) { repo.setSessionId(id) }

            override fun onToolProgress(tool: String, label: String?, running: Boolean) = onMain {
                if (turn != myTurn) return@onMain
                toolLabel.value = if (running) (label ?: tool) else null
            }

            override fun onComplete() = onMain {
                if (turn != myTurn) return@onMain
                main.removeCallbacks(idleFlush)
                main.removeCallbacks(stallIndicator)
                working.value = false
                stalled.value = false
                toolLabel.value = null
                val rest = sentenceBuffer.toString().trim()
                sentenceBuffer.setLength(0)
                if (rest.isNotEmpty()) enqueueSpeech(rest)
                pendingText.value = ""
                if (reply.value.isNotBlank()) repo.addMessage("assistant", reply.value)
                viewModelScope.launch { repo.persist() }
                streamDone = true
                pump()
            }

            override fun onError(message: String) = onMain {
                if (turn != myTurn) return@onMain
                main.removeCallbacks(stallIndicator)
                working.value = false
                stalled.value = false
                toolLabel.value = null
                error.value = message
                goIdle()
            }
        })
    }

    private suspend fun sendSessions(client: HermesClient, features: ServerFeatures, myTurn: Int, baseUrl: String) {
        val userText = repo.messages.lastOrNull { it.role == "user" && !it.isError }?.text ?: ""
        val origin = originIdentity(baseUrl)
        if (repo.transport != ChatTransportKind.SESSIONS) repo.bindTransport(origin, ChatTransportKind.SESSIONS)

        // If no server session yet, create one first
        if (repo.sessionId.isNullOrEmpty()) {
            val titleForSession = sessionTitleFrom(repo.lastUserTurnText())
            val createResult = createSessionForFirstTurn(client, titleForSession, repo.activeConversationId)
            createResult.fold(
                onSuccess = { sid ->
                    if (turn != myTurn) return@fold
                    repo.bindSession(origin, sid, ChatTransportKind.SESSIONS)
                    // Send the turn on the new session
                    if (features.session_chat_streaming) {
                        startSessionStreaming(client, repo.sessionId!!, userText, myTurn)
                    } else {
                        sendSessionTurnNonStreaming(client, repo.sessionId!!, userText, myTurn)
                    }
                },
                onFailure = {
                    if (turn != myTurn) return@fold
                    onMain {
                        error.value = "Couldn't start a Hermes session: ${it.message?.take(120)}"
                        repo.persistAsync()
                        goIdle()
                    }
                }
            )
            // If create failed or turn changed, don't proceed
            return
        }

        // Session already exists — send the turn directly
        if (features.session_chat_streaming) {
            startSessionStreaming(client, repo.sessionId!!, userText, myTurn)
        } else {
            sendSessionTurnNonStreaming(client, repo.sessionId!!, userText, myTurn)
        }
    }

    private fun startSessionStreaming(client: HermesClient, sid: String, userText: String, myTurn: Int) {
        source = client.streamSessionTurn(sid, userText, object : HermesClient.StreamCallbacks {
            override fun onDelta(textDelta: String) = onMain {
                if (turn != myTurn) return@onMain
                onTextDelta(textDelta)
            }

            override fun onFinalContent(text: String) = onMain {
                if (turn != myTurn) return@onMain
                // Replace the accumulated text exactly once (no duplication)
                reply.value = text
                sentenceBuffer.setLength(0)
                sentenceBuffer.append(text)
                extractSentences()
                pendingText.value = ""
            }

            override fun onToolProgress(tool: String, label: String?, running: Boolean) = onMain {
                if (turn != myTurn) return@onMain
                toolLabel.value = if (running) (label ?: tool) else null
            }

            override fun onComplete() = onMain {
                if (turn != myTurn) return@onMain
                main.removeCallbacks(idleFlush)
                main.removeCallbacks(stallIndicator)
                working.value = false
                stalled.value = false
                toolLabel.value = null
                val rest = sentenceBuffer.toString().trim()
                sentenceBuffer.setLength(0)
                if (rest.isNotEmpty()) enqueueSpeech(rest)
                pendingText.value = ""
                if (reply.value.isNotBlank()) repo.addMessage("assistant", reply.value)
                viewModelScope.launch { repo.persist() }
                streamDone = true
                pump()
            }

            override fun onError(message: String) = onMain {
                if (turn != myTurn) return@onMain
                main.removeCallbacks(stallIndicator)
                working.value = false
                stalled.value = false
                toolLabel.value = null
                val hermesErr = message as? HermesHttpError
                if (hermesErr?.isSessionMissing == true) {
                    error.value = "Remote session no longer exists (session_not_found). Start a new conversation from History to continue."
                } else if (hermesErr?.isAuth == true) {
                    error.value = "Authentication failed (401/403). Check the API key in Settings."
                } else {
                    error.value = message
                }
                goIdle()
            }
        })
    }

    private suspend fun sendSessionTurnNonStreaming(client: HermesClient, sid: String, userText: String, myTurn: Int) {
        val result = client.sendSessionTurn(sid, userText)
        onMain {
            if (turn != myTurn) return@onMain
            result.fold(
                onSuccess = { turnResult ->
                    val text = turnResult.text ?: "⚠️ No response content"
                    if (text.startsWith("⚠️")) {
                        error.value = text
                        goIdle()
                    } else {
                        reply.value = text
                        repo.addMessage("assistant", reply.value)
                        sentenceBuffer.setLength(0)
                        sentenceBuffer.append(text)
                        extractSentences()
                        streamDone = true
                        pendingText.value = ""
                        viewModelScope.launch { repo.persist() }
                        pump()
                    }
                },
                onFailure = {
                    val hermesErr = it as? HermesHttpError
                    if (hermesErr?.isSessionMissing == true) {
                        error.value = "Remote session no longer exists (session_not_found). Start a new conversation from History to continue."
                    } else if (hermesErr?.isAuth == true) {
                        error.value = "Authentication failed (401/403). Check the API key in Settings."
                    } else {
                        error.value = it.message?.take(120) ?: "Session turn failed"
                    }
                    goIdle()
                }
            )
        }
    }

    /** A token arrived: show it, and speak as soon as a full sentence is available. */
    private fun onTextDelta(delta: String) {
        reply.value += delta
        sentenceBuffer.append(delta)
        extractSentences()
        pendingText.value = sentenceBuffer.toString().trim()
        // Content is flowing → not stalled. Re-arm both timers.
        stalled.value = false
        main.removeCallbacks(idleFlush)
        main.postDelayed(idleFlush, IDLE_FLUSH_MS)
        main.removeCallbacks(stallIndicator)
        main.postDelayed(stallIndicator, STALL_MS)
    }

    private fun flushPendingSentence() {
        val s = sentenceBuffer.toString().trim()
        if (s.isNotEmpty() && (s.endsWith('.') || s.endsWith('!') || s.endsWith('?'))) {
            sentenceBuffer.setLength(0)
            pendingText.value = ""
            enqueueSpeech(s)
        }
    }

    private fun extractSentences() {
        while (true) {
            val sentence = SentenceSplitter.takeNext(sentenceBuffer) ?: break
            if (sentence.isNotEmpty()) enqueueSpeech(sentence)
        }
    }

    private fun enqueueSpeech(text: String) {
        ttsQueue.addLast(text)
        segments.add(text)
        pump()
    }

    /** Speak queued sentences one after another (the next synthesizes after the prior plays). */
    private fun pump() {
        if (speaking) return
        val next = ttsQueue.removeFirstOrNull()
        if (next == null) {
            if (streamDone) finishTurn()
            return
        }
        speaking = true
        speakingIndex.value = spokenCount
        spokenCount++
        if (state.value != ConvState.Speaking) state.value = ConvState.Speaking
        val engine = tts
        if (engine == null) { speaking = false; return }
        val myTurn = turn
        val spoken = BoldMarkdownStripper.strip(next)
        if (spoken.isBlank()) {
            speaking = false
            pump()
            return
        }
        engine.speak(
            text = spoken,
            onDone = { main.post { if (turn == myTurn) { speaking = false; pump() } } },
            onError = { main.post { if (turn == myTurn) { speaking = false; pump() } } },
        )
    }

    private fun finishTurn() {
        if (continuous) startListening() else goIdle()
    }

    /**
     * Go idle WITHIN the conversation. To re-engage after a silence, tap the mic.
     */
    private fun goIdle() {
        state.value = ConvState.Idle
    }

    fun onMicTap() {
        when (state.value) {
            ConvState.Listening -> { turn++; recognizer?.stop(); goIdle() }
            else -> startListening()
        }
    }

    fun stopAll() {
        continuous = false
        turn++
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        working.value = false
        stalled.value = false
        toolLabel.value = null
        recognizer?.release()
        recognizer = null
        runCatching { tts?.stop() }
        source?.cancel(); source = null
        ttsQueue.clear()
        sentenceBuffer.setLength(0)
        speaking = false
        streamDone = false
        segments.clear()
        speakingIndex.value = -1
        pendingText.value = ""
        spokenCount = 0
        state.value = ConvState.Idle
        hint.value = null
        // Save the conversation (covers turns that ended in an error/cancel, not just
        // successful replies).
        repo.persistAsync()
    }

    override fun onCleared() {
        main.removeCallbacks(idleFlush)
        main.removeCallbacks(stallIndicator)
        recognizer?.release()
        source?.cancel()
        tts?.shutdown()
        repo.persistAsync()
        super.onCleared()
    }

    private companion object {
        const val IDLE_FLUSH_MS = 350L
        const val STALL_MS = 800L
    }
}
