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
    }

    fun streamChat(
        messages: List<ChatMessage>,
        sessionId: String?,
        cb: StreamCallbacks,
        model: String? = null,
    ): EventSource {
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
                    throw RuntimeException("HTTP ${resp.code}: ${text.take(200).ifBlank { resp.message }}")
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
                    throw RuntimeException("HTTP ${resp.code}: ${text.take(200).ifBlank { resp.message }}")
                }
                HermesJson.decodeFromString(SessionDeleted.serializer(), text)
            }
        }
    }

    /** GET /api/model/options — provider/model inventory for a future model picker. */
    suspend fun getModelOptions(): Result<ModelOptionsPayload> = getJson(
        path = "api/model/options",
        serializer = ModelOptionsPayload.serializer(),
    )

    /** GET /api/sessions/{id} — fetches a single server session. */
    suspend fun getSession(sessionId: String): Result<SessionEnvelope> = getJson(
        path = "api/sessions/${java.net.URLEncoder.encode(sessionId, "UTF-8")}",
        serializer = SessionEnvelope.serializer(),
    )

    /** POST /api/sessions/{id}/model — sets a model lock on a server session. */
    suspend fun setSessionModel(sessionId: String, model: String, provider: String? = null): Result<ModelLockResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val bodyString = if (provider != null)
                """{"model":"$model","provider":"$provider","require_model_lock":true}"""
            else
                """{"model":"$model","require_model_lock":true}"""
            val req = Request.Builder()
                .url("$baseUrl/api/sessions/${java.net.URLEncoder.encode(sessionId, "UTF-8")}/model")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(bodyString.toRequestBody(JSON_MEDIA))
                .build()
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}: ${text.take(200).ifBlank { resp.message }}")
                }
                HermesJson.decodeFromString(ModelLockResponse.serializer(), text)
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
            Http.base.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw RuntimeException("HTTP ${resp.code}: ${text.take(200).ifBlank { resp.message }}")
                }
                HermesJson.decodeFromString(ModelLockResponse.serializer(), text)
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
                        throw RuntimeException("HTTP ${resp.code}: ${text.take(200).ifBlank { resp.message }}")
                    }
                    HermesJson.decodeFromString(serializer, text)
                }
            }
        }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val TOOL_PROGRESS_EVENT = "hermes.tool.progress"
    }
}
