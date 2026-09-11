package dk.foss.jarvis.hermes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

@Serializable data class ServerFeatures(
    val session_chat: Boolean = false, val session_chat_streaming: Boolean = false,
    val session_model_lock: Boolean = false, val session_model_clear: Boolean = false,
    val model_options: Boolean = false, val chat_completions: Boolean = false,
)
@Serializable data class CapabilitiesEnvelope(val features: ServerFeatures = ServerFeatures())

fun parseCapabilities(text: String): ServerFeatures? {
    val obj = runCatching { HermesJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val keys = setOf("session_chat", "session_chat_streaming", "session_model_lock", "session_model_clear", "model_options", "chat_completions")
    // Decode only objects which actually carry the capabilities schema. This
    // avoids treating {} (or an unrelated JSON response) as valid all-false
    // capabilities.
    if (obj["features"] != null) {
        val features = obj["features"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: return null
        if (features.keys.none { it in keys }) return null
        return runCatching { HermesJson.decodeFromString(CapabilitiesEnvelope.serializer(), text).features }.getOrNull()
    }
    if (obj.keys.none { it in keys }) return null
    return runCatching { HermesJson.decodeFromString(ServerFeatures.serializer(), text) }.getOrNull()
}

enum class CapabilityState { SUPPORTED, UNSUPPORTED, UNKNOWN }
data class OriginCapabilities(val origin: String, val state: CapabilityState, val features: ServerFeatures = ServerFeatures())

object CapabilityRegistry {
    private val cache = ConcurrentHashMap<String, OriginCapabilities>()
    suspend fun capabilities(origin: String, fetch: suspend () -> Result<ServerFeatures>): OriginCapabilities {
        cache[origin]?.let { return it }
        val result = runCatching { fetch() }.getOrElse { Result.failure(it) }
        result.getOrNull()?.let { features ->
            return OriginCapabilities(origin, CapabilityState.SUPPORTED, features).also { cache[origin] = it }
        }
        val error = result.exceptionOrNull()
        if (error is HermesHttpError && (error.code == 404 || error.code == 405))
            return OriginCapabilities(origin, CapabilityState.UNSUPPORTED).also { cache[origin] = it }
        return OriginCapabilities(origin, CapabilityState.UNKNOWN)
    }
    fun evict(origin: String) { cache.remove(origin) }
}
