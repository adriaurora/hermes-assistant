package dk.foss.jarvis.hermes

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable data class ServerFeatures(
    val session_chat: Boolean = false, val session_chat_streaming: Boolean = false,
    val session_model_lock: Boolean = false, val session_model_clear: Boolean = false,
    val model_options: Boolean = false, val chat_completions: Boolean = false,
)
@Serializable data class CapabilitiesEnvelope(val features: ServerFeatures = ServerFeatures())

fun parseCapabilities(text: String): ServerFeatures {
    val envelope = runCatching { HermesJson.decodeFromString(CapabilitiesEnvelope.serializer(), text).features }.getOrDefault(ServerFeatures())
    if (listOf(envelope.session_chat, envelope.session_chat_streaming, envelope.session_model_lock, envelope.session_model_clear, envelope.model_options, envelope.chat_completions).any { it }) return envelope
    return runCatching { HermesJson.decodeFromString(ServerFeatures.serializer(), text) }.getOrDefault(envelope)
}

enum class CapabilityState { SUPPORTED, UNSUPPORTED, UNKNOWN }
data class OriginCapabilities(val origin: String, val state: CapabilityState, val features: ServerFeatures = ServerFeatures())

object CapabilityRegistry {
    private val cache = ConcurrentHashMap<String, OriginCapabilities>()
    suspend fun capabilities(origin: String, fetch: suspend () -> Result<ServerFeatures>): OriginCapabilities {
        cache[origin]?.let { return it }
        val result = runCatching { fetch() }.getOrElse { Result.failure(it) }
        result.getOrNull()?.let { return OriginCapabilities(origin, CapabilityState.SUPPORTED, it).also { cache[origin] = it } }
        val error = result.exceptionOrNull()
        if (error is HermesHttpError && (error.code == 404 || error.code == 405))
            return OriginCapabilities(origin, CapabilityState.UNSUPPORTED).also { cache[origin] = it }
        return OriginCapabilities(origin, CapabilityState.UNKNOWN)
    }
    fun evict(origin: String) { cache.remove(origin) }
}
