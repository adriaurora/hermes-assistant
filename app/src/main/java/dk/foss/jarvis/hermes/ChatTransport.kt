package dk.foss.jarvis.hermes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import dk.foss.jarvis.net.E2eLog

/** Chat transport generation marker. Deliberately unrelated to the push PushProtocol enum and to FCM/device identity. */
enum class ChatTransportKind { LEGACY_CHAT, SESSIONS }

@Serializable data class SessionTurnRequest(val message: String)
@Serializable data class SessionCreateRequest(val title: String)
@Serializable data class SessionTurnResult(
    val content: String? = null, val message: String? = null, val assistant: String? = null,
    val runtime: RuntimeInfo? = null,
) { val text: String? get() = content ?: message ?: assistant }

data class HermesHttpError(val code: Int?, val rpcCode: String?, val rawBody: String?, override val message: String) : RuntimeException(message) {
    val isAuth: Boolean get() = code == 401 || code == 403 || rpcCode == "gateway_auth_failed"
    val isSessionMissing: Boolean get() = code == 404 && (rpcCode == "session_not_found" || rawBody?.contains("session_not_found") == true)
}

fun parseErrorBody(body: String): Pair<String?, String?> = runCatching {
    val obj = HermesJson.parseToJsonElement(body).jsonObject
    val error = obj["error"]
    when {
        error != null && error.toString().startsWith("{") -> {
            val e = error.jsonObject
            e["code"]?.jsonPrimitive?.contentOrNull to e["message"]?.jsonPrimitive?.contentOrNull
        }
        error != null -> null to error.jsonPrimitive.contentOrNull
        obj["detail"] != null -> null to obj["detail"]?.jsonPrimitive?.contentOrNull
        obj["message"] != null -> null to obj["message"]?.jsonPrimitive?.contentOrNull
        obj["code"] != null -> obj["code"]?.jsonPrimitive?.contentOrNull to null
        else -> null to null
    }
}.getOrElse { null to body.take(200).ifBlank { null } }

private val JsonPrimitive.contentOrNull get() = runCatching { content }.getOrNull()

/** Decision returned by [ChatTransportSelector.decide] about which chat transport to use. */
sealed class ChatTransportDecision {
    data class Sessions(val features: ServerFeatures) : ChatTransportDecision()
    object Legacy : ChatTransportDecision()
    data class Blocked(val reason: String) : ChatTransportDecision()
    data class Unavailable(val reason: String) : ChatTransportDecision()
}

object ChatTransportSelector {
    fun decide(features: OriginCapabilities?, convTransport: ChatTransportKind?, convOrigin: String?, currentOrigin: String, hasMessages: Boolean): ChatTransportDecision {
        val originMatch = convOrigin == currentOrigin
        val result = when (convTransport) {
        ChatTransportKind.LEGACY_CHAT -> ChatTransportDecision.Legacy
        ChatTransportKind.SESSIONS -> if (!originMatch) ChatTransportDecision.Unavailable("This conversation is bound to a different server (origin isolation). Start a new conversation.") else ChatTransportDecision.Sessions(features?.features ?: ServerFeatures())
        null -> when {
            features == null || features.state == CapabilityState.UNKNOWN -> ChatTransportDecision.Blocked("Unable to verify server capabilities. Check the connection and try again.")
            hasMessages -> ChatTransportDecision.Legacy
            features.state == CapabilityState.UNSUPPORTED -> ChatTransportDecision.Legacy
            features.features.session_chat -> ChatTransportDecision.Sessions(features.features)
            else -> ChatTransportDecision.Legacy
        }
        }
        val caps = features?.features?.let { "session_chat=${it.session_chat},session_chat_streaming=${it.session_chat_streaming},model_options=${it.model_options},session_model_lock=${it.session_model_lock},session_model_clear=${it.session_model_clear}" } ?: "null"
        E2eLog.log("transportDecision caps=$caps storedTransport=$convTransport originMatch=$originMatch hasMessages=$hasMessages -> ${result::class.simpleName}")
        return result
    }
}

/**
 * Canonical connection identity for conversation binding and origin isolation.
 *
 * Builds a stable hash from the full base URL (scheme + host lowercased, default port folded,
 * path preserved, no trailing slash) combined with a short non-reversible fingerprint of the API key
 * (full 64-hex SHA-256). If [apiKey] is null or blank only the URL forms the identity —
 * documented so callers know conversations on the same host but different keys stay distinct.
 */
fun originIdentity(baseUrl: String, apiKey: String? = null): String = runCatching {
    val uri = URI(baseUrl.trim().trimEnd('/'))
    val scheme = uri.scheme.lowercase()
    val host = uri.host.lowercase()
    val defaultPort = if (scheme == "http") 80 else if (scheme == "https") 443 else -1
    val port = if (uri.port != -1 && uri.port != defaultPort) ":${uri.port}" else ""
    val path = uri.path.ifEmpty { "/" }
    val urlPart = "$scheme://$host$port$path"
    val keyHash = apiKey?.let { key ->
        if (key.isBlank()) return@let null
        val md = MessageDigest.getInstance("SHA-256")
        md.update(key.toByteArray(Charsets.UTF_8))
        md.digest().joinToString("") { "%02x".format(it) }
    }
    if (keyHash != null) "$urlPart-$keyHash" else urlPart
}.getOrElse { baseUrl.trim().trimEnd('/').lowercase() }

/** Legacy identity: only scheme://host:port (no path, no key hash). */
@Deprecated("Use originIdentity(baseUrl, apiKey) for full isolation. Kept for migration.", level = DeprecationLevel.WARNING)
fun legacyOriginIdentity(baseUrl: String): String = runCatching {
    val uri = URI(baseUrl.trim().trimEnd('/'))
    val scheme = uri.scheme.lowercase()
    val host = uri.host.lowercase()
    val defaultPort = if (scheme == "http") 80 else if (scheme == "https") 443 else -1
    val port = if (uri.port != -1) uri.port else defaultPort
    "$scheme://$host:$port"
}.getOrElse { baseUrl.trim().trimEnd('/').lowercase() }
