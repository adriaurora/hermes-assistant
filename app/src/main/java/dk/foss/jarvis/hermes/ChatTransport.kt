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
enum class ChatTransportKind { SESSIONS }

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

/** Stable product wording for transport failures; never expose exception text to UI. */
fun semanticChatError(error: Throwable): String {
    val http = error as? HermesHttpError
    return when {
        error is StreamClosedBeforeTerminalError ->
            "Response stream ended before completion. Open the conversation to reconcile its latest server history."
        http?.isAuth == true -> "Authentication failed. Check the API key in Settings."
        http?.isSessionMissing == true || http?.rpcCode == "session_not_found" ->
            "Remote session no longer exists (session_not_found). Start a new conversation from History to continue."
        http?.code in 408..499 -> "Hermes rejected the request. Check the conversation and try again."
        http?.code in 500..599 -> "Hermes is temporarily unavailable. Try again later."
        error is java.util.concurrent.CancellationException -> ""
        else -> "Couldn't reach Hermes. Check the connection and try again."
    }
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
    data class Blocked(val reason: String) : ChatTransportDecision()
}

object ChatTransportSelector {
    fun decide(features: OriginCapabilities?, convTransport: ChatTransportKind?, convOrigin: String?, currentOrigin: String, hasMessages: Boolean = false): ChatTransportDecision {
        val originMatch = convOrigin == currentOrigin
        val result = when (convTransport) {
        ChatTransportKind.SESSIONS -> when {
            !originMatch -> ChatTransportDecision.Blocked("This conversation is bound to a different server (origin isolation). Start a new conversation.")
            features?.state != CapabilityState.SUPPORTED -> ChatTransportDecision.Blocked("Unable to verify server capabilities. Check the connection and try again.")
            !features.features.session_chat -> ChatTransportDecision.Blocked("This server does not support session chat. Start a new conversation on a compatible server.")
            else -> ChatTransportDecision.Sessions(features.features)
        }
        null -> when {
            hasMessages -> ChatTransportDecision.Blocked("This conversation cannot be continued. Start a new conversation.")
            features == null || features.state == CapabilityState.UNKNOWN -> ChatTransportDecision.Blocked("Unable to verify server capabilities. Check the connection and try again.")
            features.state == CapabilityState.UNSUPPORTED -> ChatTransportDecision.Blocked("This server does not support session chat.")
            features.features.session_chat -> ChatTransportDecision.Sessions(features.features)
            else -> ChatTransportDecision.Blocked("This server does not support session chat.")
        }
        }
        val caps = features?.features?.let { "session_chat=${it.session_chat},session_chat_streaming=${it.session_chat_streaming},model_options=${it.model_options},session_model_lock=${it.session_model_lock},session_model_clear=${it.session_model_clear}" } ?: "null"
        E2eLog.log("transportDecision caps=$caps storedTransport=$convTransport originMatch=$originMatch -> ${result::class.simpleName}")
        return result
    }
}

/**
 * Canonical connection identity for conversation binding and origin isolation.
 *
 * Builds a stable hash from the full base URL (scheme + host lowercased,
 * default port folded, path preserved, no trailing slash) combined with a
 * non-reversible SHA-256 fingerprint of the API key (full 64-hex digest).
 * If [apiKey] is null or blank only the URL forms the identity.
 * This preserves API-key conversation isolation: conversations on the same
 * host but different keys stay distinct.
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
        if (key.isBlank()) null
        else {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(key.toByteArray(Charsets.UTF_8))
            md.digest().joinToString("") { "%02x".format(it) }
        }
    }
    if (keyHash != null) "$urlPart-$keyHash" else urlPart
}.getOrElse { baseUrl.trim().trimEnd('/').lowercase() }

/**
 * URL-only canonical endpoint identity for Settings / gate / UI comparisons.
 *
 * Returns a stable normalised origin (scheme + lowercase host + explicit
 * default port + path, no trailing slash).  Unlike [originIdentity] this
 * **never** includes an API-key fingerprint — it is used exclusively for
 * endpoint-approval decisions where key rotation must not break continuity.
 *
 * **Strict**: throws for invalid URIs — no fallback.  Caller must validate
 * through [NetworkGate.validate] before using the result.
 */
fun canonicalEndpointIdentity(baseUrl: String): String {
    val trimmed = baseUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) throw IllegalArgumentException("Empty URL")
    val uri = URI.create(trimmed)
    if (uri.isOpaque) throw IllegalArgumentException("Malformed URL (opaque): $trimmed")
    val scheme = uri.scheme
    if (scheme == null ||
        !scheme.equals("http", true) && !scheme.equals("https", true)) {
        throw IllegalArgumentException("Unsupported scheme: $trimmed")
    }
    if (uri.host == null) throw IllegalArgumentException("Missing host: $trimmed")
    if (uri.userInfo != null) throw IllegalArgumentException("URL contains userinfo: $trimmed")
    if (uri.query != null) throw IllegalArgumentException("URL contains query: $trimmed")
    if (uri.fragment != null) throw IllegalArgumentException("URL contains fragment: $trimmed")

    val lowerScheme = scheme.lowercase()
    val host = uri.host.lowercase()
    val defaultPort = if (lowerScheme == "http") 80 else 443
    val port = if (uri.port != -1 && uri.port != defaultPort) ":${uri.port}" else ""
    val path = uri.path.ifEmpty { "/" }
    return "$lowerScheme://$host$port$path"
}
