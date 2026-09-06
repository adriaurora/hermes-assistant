package dk.foss.jarvis.hermes

import dk.foss.jarvis.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.net.URI
import java.io.IOException
import dk.foss.jarvis.push.isValidHermesEventId

enum class FetchFailureKind { HTTP, SERIALIZATION, NETWORK, OTHER }

class EventFetchException(
    val kind: FetchFailureKind,
    val statusCode: Int? = null,
    cause: Throwable? = null,
    val rpcCode: String? = null,
) : Exception("event fetch failed: ${kind.name.lowercase()}${statusCode?.let { " ($it)" }.orEmpty()}${rpcCode?.let { " [$it]" }.orEmpty()}", cause)

class HermesHttpException(val statusCode: Int, cause: Throwable? = null) : IOException("HTTP $statusCode", cause)

/** REST client for Hermes device registration and durable event operations. */
interface EventApi {
    suspend fun fetchEvent(id: String): Result<HermesEvent>
    suspend fun ack(id: String): Result<Unit>
    suspend fun pending(): Result<HermesEventsPage>
}

class EventClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val deviceId: String? = null,
) : EventApi {
    suspend fun registerFcmDevice(token: String): Result<DeviceRegisterResponse> = postJson(
        "api/devices/register", fcmRegisterBody(token, deviceId?.takeIf { it.isNotBlank() }), DeviceRegisterResponse.serializer(),
    )

    suspend fun updateFcmToken(token: String): Result<DeviceOpsResponse> =
        postJson("api/devices/${requiredDeviceId()}/token", fcmTokenBody(token), DeviceOpsResponse.serializer())

    /** DELETE /api/devices/{device_id} — revokes the authenticated device registration. */
    suspend fun revokeDevice(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/${deviceRevokePath(requiredDeviceId())}")
                .addHeader("Authorization", "Bearer $apiKey").delete().build()
            Http.base.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw HermesHttpException(response.code)
            }
        }
    }

    override suspend fun fetchEvent(id: String): Result<HermesEvent> {
        if (!isValidHermesEventId(id)) return Result.failure(EventFetchException(FetchFailureKind.OTHER))
        return getJson("api/events/${encoded(id)}", HermesEvent.serializer()).mapCatching {
            if (it.event_id != id) throw EventFetchException(FetchFailureKind.SERIALIZATION,
                cause = IllegalArgumentException("event_id does not match requested id"))
            it
        }
    }

    override suspend fun pending(): Result<HermesEventsPage> = getJson(
        "api/events?status=pending&device_id=${encoded(requiredDeviceId())}", HermesEventsPage.serializer(),
    )

    override suspend fun ack(id: String): Result<Unit> = ackEvent(id).map { Unit }

    suspend fun fetchPendingEvents(): Result<HermesEventsPage> = pending()

    suspend fun ackEvent(eventId: String): Result<DeviceOpsResponse> =
        if (!isValidHermesEventId(eventId)) Result.failure(IllegalArgumentException("invalid event id"))
        else postJson("api/events/${encoded(eventId)}/ack", "{}", DeviceOpsResponse.serializer())

    private fun requiredDeviceId(): String = deviceId?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("A registered device_id is required")

    private suspend fun <T> getJson(path: String, serializer: KSerializer<T>): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/$path")
                .addHeader("Authorization", "Bearer $apiKey").get().build()
            Http.base.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw HttpFetchFailure(response.code)
                HermesJson.decodeFromString(serializer, text)
            }
        }.recoverCatching { throw classifyFetchFailure(it) }
    }

    private fun classifyFetchFailure(error: Throwable): EventFetchException = when (error) {
        is EventFetchException -> error
        is HttpFetchFailure -> EventFetchException(FetchFailureKind.HTTP, error.statusCode, error)
        is SerializationException -> EventFetchException(FetchFailureKind.SERIALIZATION, cause = error)
        is IOException -> EventFetchException(FetchFailureKind.NETWORK, cause = error)
        else -> EventFetchException(FetchFailureKind.OTHER, cause = error)
    }

    private suspend fun <T> postJson(path: String, body: String, serializer: KSerializer<T>): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/$path")
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json; charset=utf-8")
                .post(body.toRequestBody(JSON_MEDIA)).build()
            Http.base.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "HTTP ${response.code}: ${text.take(200).ifBlank { response.message }}" }
                HermesJson.decodeFromString(serializer, text)
            }
        }
    }

    companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
        internal fun deviceRevokePath(deviceId: String) = "api/devices/${encoded(deviceId)}"
        /** Canonical identity used for connection changes (not URL text). */
        fun originIdentity(baseUrl: String): String = runCatching {
            val uri = URI(baseUrl.trim().trimEnd('/'))
            val scheme = uri.scheme.lowercase()
            val host = uri.host.lowercase()
            val port = if (uri.port != -1) uri.port else when (scheme) {
                "http" -> 80
                "https" -> 443
                else -> -1
            }
            "$scheme://$host:$port"
        }.getOrElse { baseUrl.trim().trimEnd('/').lowercase() }
        internal fun fcmRegisterBody(token: String, deviceId: String? = null) =
            HermesJson.encodeToString(FcmRegisterBody.serializer(), FcmRegisterBody("fcm", token, deviceId))
        internal fun fcmTokenBody(token: String) =
            HermesJson.encodeToString(FcmTokenBody.serializer(), FcmTokenBody("fcm", token))
    }

    @Serializable private data class FcmRegisterBody(val push_type: String, val push_token: String, val device_id: String? = null)
    @Serializable private data class FcmTokenBody(val push_type: String, val push_token: String)

    private class HttpFetchFailure(val statusCode: Int) : IOException()
}
