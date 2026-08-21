package dk.foss.jarvis.hermes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ChatMessage(val role: String, val content: String)

/**
 * OpenAI-compatible chat request. [model] is intentionally optional: when
 * null it is OMITTED from the JSON body so Hermes stays authoritative for
 * model selection (session /model override → session-persisted model →
 * gateway default). Verified against hermes-agent v0.20.4 api_server.
 */
@Serializable
data class ChatRequest(
    val model: String? = null,
    val messages: List<ChatMessage>,
    val stream: Boolean = true,
)

/** Shared wire format: unknown fields tolerated, nulls omitted from output. */
val HermesJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// --- streaming response (OpenAI chat.completion.chunk) ---

@Serializable
data class StreamChunk(val choices: List<StreamChoice> = emptyList())

@Serializable
data class StreamChoice(
    val delta: Delta = Delta(),
    val finish_reason: String? = null,
)

@Serializable
data class Delta(val role: String? = null, val content: String? = null)

// --- /v1/models (connection test) ---

@Serializable
data class ModelsResponse(val data: List<ModelEntry> = emptyList())

@Serializable
data class ModelEntry(val id: String)
