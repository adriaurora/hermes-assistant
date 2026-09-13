package dk.foss.jarvis.hermes

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ChatMessage(val role: String, val content: String)

/**
 * OpenAI-compatible chat request. [model] is intentionally optional: when
 * null it is OMITTED from the JSON body so Hermes stays authoritative for
 * model selection (session /model override → session-persisted model →
 * gateway default). Verified against hermes-agent v0.20.4 api_server.
 */
/** Shared wire format: unknown fields tolerated, nulls omitted from output. */
@OptIn(ExperimentalSerializationApi::class)
val HermesJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

// --- streaming response (OpenAI chat.completion.chunk) ---

// --- /v1/models (connection test) ---

@Serializable
data class ModelsResponse(val data: List<ModelEntry> = emptyList())

@Serializable
data class ModelEntry(val id: String)

// --- durable events and device registration ---

@Serializable
data class HermesEvent(
    val event_id: String,
    val event_type: String,
    val created_at: Double,
    val available_at: Double,
    val expires_at: Double? = null,
    val source: String? = null,
    val source_id: String? = null,
    val session_id: String? = null,
    val title: String? = null,
    val body: String? = null,
    @Serializable(with = EventPrioritySerializer::class)
    val priority: Int = 0,
    val status: String? = null,
    val device_id: String? = null,
    val state: String? = null,
    val delivered_at: Double? = null,
    val acknowledged_at: Double? = null,
)

/** Hermes sends event priority as low/normal/high; retain integer compatibility for older servers. */
@OptIn(ExperimentalSerializationApi::class)
internal object EventPrioritySerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("EventPriority", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Int {
        val element = (decoder as? JsonDecoder)?.decodeJsonElement()
        val value = (element as? JsonPrimitive)?.content ?: decoder.decodeString()
        return when (value.lowercase()) {
            "low" -> 0
            "normal" -> 1
            "high" -> 2
            else -> value.toIntOrNull() ?: 0
        }
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

@Serializable
data class HermesEventsPage(val events: List<HermesEvent> = emptyList())

@Serializable
data class DeviceRegisterResponse(val device_id: String, val status: String)

@Serializable
data class DeviceOpsResponse(val status: String)

// --- hermes.tool.progress SSE frame (tool activity during a streamed turn) ---

/** Data payload of an `event: hermes.tool.progress` SSE frame; status is running|completed. */
@Serializable
data class ToolProgress(
    val tool: String = "",
    val label: String? = null,
    val emoji: String? = null,
    val status: String = "",
    val toolCallId: String? = null,
)

// --- /api/sessions (server-backed history) ---
// Verified against the installed api_server `_session_response`: only a
// client-safe subset of columns is exposed; unknown keys are tolerated.

@Serializable
data class SessionsPage(
    val data: List<SessionSummary> = emptyList(),
    val has_more: Boolean = false,
)

@Serializable
data class SessionSummary(
    val id: String,
    val source: String? = null,
    val title: String? = null,
    val preview: String? = null,
    @Serializable(with = EpochMillisSerializer::class)
    val started_at: Long? = null,
    @Serializable(with = EpochMillisSerializer::class)
    val ended_at: Long? = null,
    @Serializable(with = EpochMillisSerializer::class)
    val last_active: Long? = null,
    val message_count: Int = 0,
    val pinned: Boolean = false,
    val model: String? = null,
)

@Serializable
data class SessionEnvelope(
    val session: SessionSummary = SessionSummary(id = ""),
    val runtime: RuntimeInfo? = null,
)

@Serializable
data class RuntimeInfo(
    val provider: String? = null, val model: String? = null,
    val route_source: String? = null, val model_lock: String? = null,
)

@Serializable
data class ModelLockResponse(
    val `object`: String? = null,
    val session_id: String? = null,
    val automatic: Boolean = false,
    val runtime: RuntimeInfo? = null,
)

@Serializable
data class SessionMessagesPage(
    val session_id: String? = null,
    val data: List<SessionMessage> = emptyList(),
)

/** Raw persisted turn row; roles include user/assistant and internal ones (tool). */
@Serializable
data class SessionMessage(
    val role: String = "",
    val content: String = "",
    @Serializable(with = EpochMillisSerializer::class)
    val timestamp: Long? = null,
)

@Serializable
data class SessionDeleted(val id: String? = null, val deleted: Boolean = false)

// --- /api/model/options (model-switching groundwork; server stays authoritative) ---

/**
 * Tolerant slice of the shared picker inventory: `{providers: [...], model, provider}`.
 * Rows carry slug/name/models plus richer picker metadata we ignore for now.
 */
@Serializable
data class ModelOptionsPayload(
    val providers: List<ProviderRow> = emptyList(),
    val model: String? = null,
    val provider: String? = null,
)

@Serializable
data class ProviderRow(
    val slug: String = "",
    val name: String? = null,
    val models: List<String> = emptyList(),
    val total_models: Int = 0,
    val is_current: Boolean = false,
    val authenticated: Boolean = false,
)

/**
 * Server timestamps arrive as epoch-seconds numbers (SQLite REAL); accept ISO
 * strings too so older/newer servers stay decodable.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object EpochMillisSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("EpochMillis", PrimitiveKind.LONG).nullable

    override fun deserialize(decoder: Decoder): Long? {
        if (decoder is JsonDecoder) return epochToMillis(decoder.decodeJsonElement())
        if (decoder.decodeNotNullMark()) return decoder.decodeLong() else { decoder.decodeNull(); return null }
    }

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) encoder.encodeNull() else encoder.encodeLong(value)
    }

    private fun epochToMillis(el: JsonElement): Long? {
        val prim = el as? JsonPrimitive ?: return null
        if (!prim.isString) return prim.content.toDoubleOrNull()?.let { (it * 1000).toLong() }
        runCatching { return java.time.Instant.parse(prim.content).toEpochMilli() }
        runCatching {
            return java.time.LocalDateTime.parse(prim.content)
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        }
        return prim.content.toDoubleOrNull()?.let { (it * 1000).toLong() }
    }
}
