package dk.foss.jarvis.hermes

import dk.foss.jarvis.hermes.HermesJson
import dk.foss.jarvis.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSources
import okhttp3.sse.EventSourceListener
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import dk.foss.jarvis.net.E2eLog

/**
 * Talks to a Hermes `api_server`. This is the ONLY coupling to Hermes:
 * OpenAI-compatible `/v1/chat/completions` (streamed via SSE), plus the
 * session-history, model-inventory and connection-test endpoints. Bearer
 * auth; session continuity via X-Hermes-Session-Id.
 */
class HermesClient(
    private val baseUrl: String,
    private val apiKey: String,
) {
    interface StreamCallbacks {
        fun onDelta(textDelta: String)
        fun onSessionId(id: String) {}

        /** Emitted for each `hermes.tool.progress` frame; [running] is false once completed. */
        fun onToolProgress(tool: String, label: String?, running: Boolean) {}

        fun onComplete() {}
        fun onError(message: String) {}
        fun onFinalContent(text: String) {}
        fun onRuntime(info: RuntimeInfo) {}
    }

    fun streamChat(
        messages: List<ChatMessage>,
        sessionId: String?,
        cb: StreamCallbacks,
        model: String? = null,
    ): EventSource {
        E2eLog.log("chat transport=LEGACY endpoint=POST /v1/chat/completions model=${model ?: "null"}")
        val body = HermesJson.encodeToString(
            ChatRequest.serializer(),
            ChatRequest(model = model, messages = messages, stream = true),
        )
        val builder = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA))
        if (!sessionId.isNullOrEmpty()) builder.addHeader("X-Hermes-Session-Id", sessionId)

        // The stream signals end twice (the "[DONE]" event AND onClosed) — make sure
        // the terminal callback fires exactly once.
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        val listener = object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                response.header("X-Hermes-Session-Id")?.let { cb.onSessionId(it) }
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                if (data.isBlank() || data == "[DONE]") {
                    if (data == "[DONE]" && finished.compareAndSet(false, true)) cb.onComplete()
                    return
                }
                if (type == TOOL_PROGRESS_EVENT) {
                    runCatching { HermesJson.decodeFromString(ToolProgress.serializer(), data) }.getOrNull()
                        ?.let { p ->
                            val label = p.label?.takeIf { it.isNotBlank() } ?: p.tool
                            cb.onToolProgress(p.tool, label, running = p.status.equals("running", ignoreCase = true))
                        }
                    return
                }
                try {
                    val chunk = HermesJson.decodeFromString(StreamChunk.serializer(), data)
                    val delta = chunk.choices.firstOrNull()?.delta?.content
                    if (!delta.isNullOrEmpty()) cb.onDelta(delta)
                } catch (_: Exception) {
                    // keep-alive comment or non-JSON line — ignore
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (finished.compareAndSet(false, true)) cb.onComplete()
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (!finished.compareAndSet(false, true)) return
                val msg = when {
                    response != null && !response.isSuccessful -> {
                        val detail = runCatching { response.body?.string() }.getOrNull()?.take(300)
                        "HTTP ${response.code}${if (!detail.isNullOrBlank()) ": $detail" else ""}"
                    }
                    t != null -> t.message ?: "Connection failed"
                    else -> "Connection failed"
                }
                cb.onError(msg)
            }
        }
        return EventSources.createFactory(Http.streaming).newEventSource(builder.build(), listener)
    }

    /** GET /v1/models — returns model ids on success, or a failure with the reason. */
    suspend fun testConnection(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/v1/models")
                .addHeader("Authorization", "Bearer $apiKey")
                .get()
                .build()
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw httpError(resp.code, text, resp.message)
                }
                HermesJson.decodeFromString(ModelsResponse.serializer(), text).data.map { it.id }
            }
        }
    }

    /** GET /api/sessions — recent Hermes sessions across all platforms (history parity). */
    suspend fun listSessions(limit: Int = 50): Result<SessionsPage> = getJson(
        path = "api/sessions?limit=${limit.coerceIn(1, 200)}",
        serializer = SessionsPage.serializer(),
    )

    /** GET /api/sessions/{id}/messages — oldest-first transcript of one server session. */
    suspend fun getSessionMessages(sessionId: String, limit: Int = 500): Result<SessionMessagesPage> = getJson(
        path = "api/sessions/${java.net.URLEncoder.encode(sessionId, "UTF-8")}/messages" +
            "?order=oldest&limit=${limit.coerceIn(1, 500)}",
        serializer = SessionMessagesPage.serializer(),
    )

    /** DELETE /api/sessions/{id} — removes a session (and its transcript) server-side. */
    suspend fun deleteSession(sessionId: String): Result<SessionDeleted> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/${java.net.URLEncoder.encode(sessionId, "UTF-8")}")
                .addHeader("Authorization", "Bearer $apiKey")
                .delete()
                .build()
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw httpError(resp.code, text, resp.message)
                }
                HermesJson.decodeFromString(SessionDeleted.serializer(), text)
            }
        }
    }

    /** GET /api/model/options — provider/model inventory for a future model picker. */
    suspend fun getModelOptions(): Result<ModelOptionsPayload> = getJson(path = "api/model/options", serializer = ModelOptionsPayload.serializer())
        .onSuccess { E2eLog.log("modelOptions default=${it.model} count=${it.providers.sumOf { p -> p.models.size }}") }

    /** GET /api/sessions/{id} — fetches a single server session. */
    suspend fun getSession(sessionId: String): Result<SessionEnvelope> = getJson(path = "api/sessions/${java.net.URLEncoder.encode(sessionId, "UTF-8")}", serializer = SessionEnvelope.serializer())


    /** POST /api/sessions/{id}/model — sets a model lock on a server session. */
    suspend fun setSessionModel(sessionId: String, model: String): Result<ModelLockResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val bodyString = """{"model":"$model"}"""
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/${java.net.URLEncoder.encode(sessionId, "UTF-8")}/model")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(bodyString.toRequestBody(JSON_MEDIA))
                .build()
            E2eLog.log("setModel POST /api/sessions/$sessionId/model body=$bodyString")
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw httpError(resp.code, text, resp.message)
                }
                HermesJson.decodeFromString(ModelLockResponse.serializer(), text).also {
                    E2eLog.log("setModel resp automatic=${it.automatic} route=${it.runtime?.route_source} model=${it.runtime?.model}")
                }
            }
        }
    }

    /** POST /api/sessions/{id}/model — clears the model lock (model:null). */
    suspend fun clearSessionModel(sessionId: String): Result<ModelLockResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val bodyString = """{"model":null}"""
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/${java.net.URLEncoder.encode(sessionId, "UTF-8")}/model")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(bodyString.toRequestBody(JSON_MEDIA))
                .build()
            E2eLog.log("clearModel POST /api/sessions/$sessionId/model body=$bodyString")
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw httpError(resp.code, text, resp.message)
                }
                HermesJson.decodeFromString(ModelLockResponse.serializer(), text).also {
                    E2eLog.log("clearModel resp automatic=${it.automatic} route=${it.runtime?.route_source} model=${it.runtime?.model}")
                }
            }
        }
    }

    /** GET /v1/capabilities — feature discovery. */
    suspend fun getCapabilities(): Result<ServerFeatures> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/v1/capabilities").addHeader("Authorization", "Bearer $apiKey").get().build()
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw httpError(resp.code, text, resp.message)
                parseCapabilities(text)
            }
        }
    }

    suspend fun createSession(title: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = HermesJson.encodeToString(SessionCreateRequest.serializer(), SessionCreateRequest(title))
            val req = Request.Builder().url("$baseUrl/api/sessions").addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json; charset=utf-8").post(body.toRequestBody(JSON_MEDIA)).build()
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw httpError(resp.code, text, resp.message)
                val id = runCatching { HermesJson.decodeFromString(SessionEnvelope.serializer(), text).session.id.takeIf { it.isNotEmpty() } ?: error("missing session id") }
                    .recoverCatching { HermesJson.decodeFromString(SessionIdOnly.serializer(), text).id }.getOrThrow()
                E2eLog.log("createSession sid=$id")
                id
            }
        }
    }

    fun streamSessionTurn(sessionId: String, message: String, cb: StreamCallbacks): EventSource {
        E2eLog.log("chat transport=SESSIONS sid=$sessionId endpoint=POST /api/sessions/$sessionId/chat/stream")
        val body = HermesJson.encodeToString(SessionTurnRequest.serializer(), SessionTurnRequest(message))
        val builder = Request.Builder().url("$baseUrl/api/sessions/$sessionId/chat/stream")
            .addHeader("Authorization", "Bearer $apiKey").addHeader("Accept", "text/event-stream")
            .post(body.toRequestBody(JSON_MEDIA))
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        val listener = object : EventSourceListener() {
            override fun onEvent(es: EventSource, id: String?, type: String?, data: String) {
                if (finished.get()) return
                E2eLog.log("sse event=${type ?: "null"}")
                when (type) {
                    "assistant.delta" -> {
                        val d = runCatching { HermesJson.decodeFromString(SessionSseData.serializer(), data).text }.getOrNull()
                        cb.onDelta(d ?: data)
                    }
                    "assistant.completed" -> runCatching { HermesJson.decodeFromString(SessionSseData.serializer(), data).content }.getOrNull()?.let(cb::onFinalContent)
                    "tool.started", "tool.progress", "tool.completed", "tool.failed" -> runCatching { HermesJson.decodeFromString(ToolProgress.serializer(), data) }.getOrNull()?.let { cb.onToolProgress(it.tool, it.label, type == "tool.started" || type == "tool.progress" || it.status.equals("running", true)) }
                    "run.completed" -> { runCatching { HermesJson.decodeFromString(SessionSseData.serializer(), data).runtime }.getOrNull()?.let(cb::onRuntime); if (finished.compareAndSet(false, true)) cb.onComplete() }
                    "done" -> if (finished.compareAndSet(false, true)) cb.onComplete()
                    "error" -> if (finished.compareAndSet(false, true)) cb.onError(runCatching { HermesJson.decodeFromString(SessionSseData.serializer(), data).message ?: HermesJson.decodeFromString(SessionSseData.serializer(), data).error }.getOrNull() ?: parseErrorBody(data).second ?: data.take(200))
                }
            }
            override fun onClosed(es: EventSource) { if (finished.compareAndSet(false, true)) cb.onComplete() }
            override fun onFailure(es: EventSource, t: Throwable?, response: Response?) {
                if (!finished.compareAndSet(false, true)) return
                val msg = if (response != null && !response.isSuccessful) "HTTP ${response.code}${runCatching { response.body?.string() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()}" else t?.message ?: "Connection failed"
                cb.onError(msg)
            }
        }
        return EventSources.createFactory(Http.streaming).newEventSource(builder.build(), listener)
    }

    suspend fun sendSessionTurn(sessionId: String, message: String): Result<SessionTurnResult> = withContext(Dispatchers.IO) {
        runCatching {
            val body = HermesJson.encodeToString(SessionTurnRequest.serializer(), SessionTurnRequest(message))
            val req = Request.Builder().url("$baseUrl/api/sessions/$sessionId/chat").addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json; charset=utf-8").post(body.toRequestBody(JSON_MEDIA)).build()
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty(); if (!resp.isSuccessful) throw httpError(resp.code, text, resp.message)
                val result = HermesJson.decodeFromString(SessionTurnResult.serializer(), text)
                if (result.text == null) throw RuntimeException(text.take(200))
                E2eLog.log("sendSessionTurn resp route=${result.runtime?.route_source} model=${result.runtime?.model}")
                result
            }
        }
    }

    private suspend fun <T> getJson(path: String, serializer: kotlinx.serialization.KSerializer<T>): Result<T> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$baseUrl/$path")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                Http.base.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw httpError(resp.code, text, resp.message)
                    }
                    HermesJson.decodeFromString(serializer, text)
                }
            }
        }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val TOOL_PROGRESS_EVENT = "hermes.tool.progress"
        fun httpError(code: Int, body: String, fallback: String) : HermesHttpError {
            val parsed = parseErrorBody(body)
            return HermesHttpError(code, parsed.first, body.take(200), "HTTP $code: ${body.take(200).ifBlank { fallback }}")
        }
    }

    @Serializable private data class SessionIdOnly(val id: String)
    @Serializable private data class SessionSseData(
        val delta: String? = null, val content: String? = null, val message: String? = null,
        val error: String? = null, val detail: String? = null, val runtime: RuntimeInfo? = null,
    ) { val text: String? get() = delta ?: content ?: message }
}
