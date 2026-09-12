package dk.foss.jarvis.hermes

import dk.foss.jarvis.net.Http
import dk.foss.jarvis.push.isValidHermesEventId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

const val RPC_PROTOCOL_VERSION = 1
const val RPC_PATH = "api/platforms/hermes_assistant/events"

/** Minimal API for durable event operations (fetch, ack, pending). Implemented by [EventRpcClient]. */
interface EventApi {
    suspend fun fetchEvent(id: String): Result<HermesEvent>
    suspend fun ack(id: String): Result<Unit>
    suspend fun pending(): Result<HermesEventsPage>
}

enum class FetchFailureKind { HTTP, SERIALIZATION, NETWORK, OTHER }

class EventFetchException(
    val kind: FetchFailureKind,
    val statusCode: Int? = null,
    cause: Throwable? = null,
    val rpcCode: String? = null,
) : Exception("event fetch failed: ${kind.name.lowercase()}${statusCode?.let { " ($it)" }.orEmpty()}${rpcCode?.let { " [$it]" }.orEmpty()}", cause)

class HermesHttpException(val statusCode: Int, cause: Throwable? = null) : IOException("HTTP $statusCode", cause)

@Serializable
data class RpcErrorBody(val code: String, val message: String? = null, @kotlinx.serialization.SerialName("http_status") val httpStatus: Int? = null)

@Serializable
data class RpcRegisterResult(val device_id: String, val device_secret: String? = null, val state: String? = null, val existing: Boolean = false)

@Serializable
data class RpcDeviceStateResult(val device_id: String? = null, val state: String? = null, val event_id: String? = null)

class RpcLogicError(val code: String, message: String?, val httpStatus: Int?, cause: Throwable? = null) :
    Exception("RPC $code${httpStatus?.let { " (HTTP $it)" }.orEmpty()}", cause)

@Serializable
private data class RpcEnvelope(
    val ok: Boolean = false,
    val protocol_version: Int? = null,
    val result: JsonElement? = null,
    val error: RpcErrorBody? = null,
)

@Serializable private data class RpcPushBody(val type: String = "fcm", val token: String)
@Serializable private data class RpcRegisterBody(val protocol_version: Int = RPC_PROTOCOL_VERSION, val type: String = "device.register", val label: String, val push: RpcPushBody, val device_id: String? = null, val device_secret: String? = null)
@Serializable private data class RpcTokenBody(val protocol_version: Int = RPC_PROTOCOL_VERSION, val type: String = "device.token.update", val device_id: String, val device_secret: String, val push_token: String)
@Serializable private data class RpcRevokeBody(val protocol_version: Int = RPC_PROTOCOL_VERSION, val type: String = "device.revoke", val device_id: String, val device_secret: String)
@Serializable private data class RpcGetBody(val protocol_version: Int = RPC_PROTOCOL_VERSION, val type: String = "event.get", val device_id: String, val device_secret: String, val event_id: String)
@Serializable private data class RpcAckBody(val protocol_version: Int = RPC_PROTOCOL_VERSION, val type: String = "event.ack", val device_id: String, val device_secret: String, val event_id: String)
@Serializable private data class RpcPendingBody(val protocol_version: Int = RPC_PROTOCOL_VERSION, val type: String = "events.pending", val device_id: String, val device_secret: String, val limit: Int)
@Serializable private data class RpcProbeBody(val protocol_version: Int = RPC_PROTOCOL_VERSION, val type: String = "events.pending", val device_id: String = "00000000-0000-0000-0000-000000000000", val device_secret: String = "capability-probe")

class EventRpcClient(private val baseUrl: String, private val apiKey: String, private val deviceId: String? = null, private val deviceSecret: String? = null) : EventApi {
    private val url = "${baseUrl.trimEnd('/')}/$RPC_PATH"
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun register(label: String, token: String, deviceId: String? = null, deviceSecret: String? = null): Result<RpcRegisterResult> =
        call(RpcRegisterBody(label = label, push = RpcPushBody(token = token), device_id = deviceId, device_secret = deviceSecret), RpcRegisterResult.serializer())

    suspend fun updateToken(token: String): Result<Unit> {
        val creds = credentials() ?: return Result.failure(IllegalStateException("A registered device is required"))
        return call(RpcTokenBody(device_id = creds.first, device_secret = creds.second, push_token = token), RpcDeviceStateResult.serializer(), acceptsNull = true).map { Unit }
    }

    suspend fun revoke(): Result<Unit> {
        val creds = credentials() ?: return Result.failure(IllegalStateException("A registered device is required"))
        return call(RpcRevokeBody(device_id = creds.first, device_secret = creds.second), RpcDeviceStateResult.serializer(), acceptsNull = true).map { Unit }
    }

    override suspend fun fetchEvent(id: String): Result<HermesEvent> {
        if (!isValidHermesEventId(id)) return Result.failure(EventFetchException(FetchFailureKind.OTHER))
        val creds = credentials() ?: return Result.failure(IllegalStateException("A registered device is required"))
        return call(RpcGetBody(device_id = creds.first, device_secret = creds.second, event_id = id), HermesEvent.serializer()).fold(
            { event -> if (event.event_id == id) Result.success(event) else Result.failure(EventFetchException(FetchFailureKind.SERIALIZATION, cause = IllegalArgumentException("event_id does not match requested id"))) },
            { Result.failure(it) })
    }

    override suspend fun ack(id: String): Result<Unit> {
        if (!isValidHermesEventId(id)) return Result.failure(IllegalArgumentException("invalid event id"))
        val creds = credentials() ?: return Result.failure(IllegalStateException("A registered device is required"))
        return call(RpcAckBody(device_id = creds.first, device_secret = creds.second, event_id = id), RpcDeviceStateResult.serializer(), acceptsNull = true).fold(
            { Result.success(Unit) },
            { if ((it as? EventFetchException)?.rpcCode == "event_not_found") Result.success(Unit) else Result.failure(it) })
    }

    override suspend fun pending(): Result<HermesEventsPage> = pending(50)
    suspend fun pending(limit: Int): Result<HermesEventsPage> {
        val creds = credentials() ?: return Result.failure(IllegalStateException("A registered device is required"))
        return call(RpcPendingBody(device_id = creds.first, device_secret = creds.second, limit = limit.coerceIn(1, 100)), HermesEventsPage.serializer())
    }

    suspend fun probe(): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val body = HermesJson.encodeToString(RpcProbeBody.serializer(), RpcProbeBody())
            val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $apiKey").addHeader("Content-Type", "application/json; charset=utf-8").post(body.toRequestBody(jsonMedia)).build()
            Http.base.newCall(request).execute().use { it.code }
        }.recoverCatching { throw classify(it) }
    }

    private fun credentials(): Pair<String, String>? = deviceId?.takeIf { it.isNotBlank() }?.let { id -> deviceSecret?.takeIf { it.isNotBlank() }?.let { id to it } }

    private suspend fun <T> call(body: Any, serializer: KSerializer<T>, acceptsNull: Boolean = false): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = when (body) {
                is RpcRegisterBody -> HermesJson.encodeToString(RpcRegisterBody.serializer(), body)
                is RpcTokenBody -> HermesJson.encodeToString(RpcTokenBody.serializer(), body)
                is RpcRevokeBody -> HermesJson.encodeToString(RpcRevokeBody.serializer(), body)
                is RpcGetBody -> HermesJson.encodeToString(RpcGetBody.serializer(), body)
                is RpcAckBody -> HermesJson.encodeToString(RpcAckBody.serializer(), body)
                is RpcPendingBody -> HermesJson.encodeToString(RpcPendingBody.serializer(), body)
                else -> error("unsupported RPC body")
            }
            val request = Request.Builder().url(url).addHeader("Authorization", "Bearer $apiKey").addHeader("Content-Type", "application/json; charset=utf-8").post(encoded.toRequestBody(jsonMedia)).build()
            Http.base.newCall(request).execute().use { response ->
                if (response.code !in 200..299) throw HermesHttpException(response.code)
                val envelope = HermesJson.decodeFromString(RpcEnvelope.serializer(), response.body?.string().orEmpty())
                if (envelope.protocol_version != null && envelope.protocol_version != RPC_PROTOCOL_VERSION) throw RpcLogicError("unsupported_protocol", "protocol_version ${envelope.protocol_version}", null)
                if (!envelope.ok) {
                    val e = envelope.error
                    throw if (e == null) RpcLogicError("invalid_response", null, null) else RpcLogicError(e.code, e.message, e.httpStatus)
                }
                val result = envelope.result
                if (result == null || result is JsonNull) {
                    @Suppress("UNCHECKED_CAST")
                    if (acceptsNull) Unit as T else throw RpcLogicError("invalid_response", null, null)
                } else HermesJson.decodeFromJsonElement(serializer, result)
            }
        }.recoverCatching { throw classify(it) }
    }

    private fun classify(error: Throwable): EventFetchException = when (error) {
        is EventFetchException -> error
        is RpcLogicError -> EventFetchException(FetchFailureKind.HTTP, error.httpStatus, error, error.code)
        is HermesHttpException -> EventFetchException(FetchFailureKind.HTTP, error.statusCode, error)
        is SerializationException -> EventFetchException(FetchFailureKind.SERIALIZATION, cause = error)
        is IOException -> EventFetchException(FetchFailureKind.NETWORK, cause = error)
        else -> EventFetchException(FetchFailureKind.OTHER, cause = error)
    }
}
