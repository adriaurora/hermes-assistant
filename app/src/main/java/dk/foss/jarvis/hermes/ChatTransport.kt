package dk.foss.jarvis.hermes

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

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
    fun decide(features: OriginCapabilities?, convTransport: ChatTransportKind?, convOrigin: String?, currentOrigin: String, hasMessages: Boolean): ChatTransportDecision = when (convTransport) {
        ChatTransportKind.LEGACY_CHAT -> ChatTransportDecision.Legacy
        ChatTransportKind.SESSIONS -> if (convOrigin != currentOrigin) ChatTransportDecision.Unavailable("This conversation is bound to a different server (origin isolation). Start a new conversation.") else ChatTransportDecision.Sessions(features?.features ?: ServerFeatures())
        null -> when {
            hasMessages -> ChatTransportDecision.Legacy
            features == null || features.state == CapabilityState.UNKNOWN -> ChatTransportDecision.Blocked("Unable to verify server capabilities. Check the connection and try again.")
            features.state == CapabilityState.UNSUPPORTED -> ChatTransportDecision.Legacy
            features.features.session_chat -> ChatTransportDecision.Sessions(features.features)
            else -> ChatTransportDecision.Legacy
        }
    }
}

fun originIdentity(baseUrl: String): String = runCatching {
    val uri = URI(baseUrl.trim().trimEnd('/'))
    val scheme = uri.scheme.lowercase(); val host = uri.host.lowercase()
    val port = if (uri.port != -1) uri.port else if (scheme == "http") 80 else if (scheme == "https") 443 else -1
    "$scheme://$host:$port"
}.getOrElse { baseUrl.trim().trimEnd('/').lowercase() }
