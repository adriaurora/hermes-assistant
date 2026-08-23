package dk.foss.jarvis.hermes

import dk.foss.jarvis.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/** REST client for Hermes device registration and durable event operations. */
class EventClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val deviceId: String? = null,
) {
    suspend fun registerDevice(endpoint: String): Result<DeviceRegisterResponse> = postJson(
        "api/devices/register",
        HermesJson.encodeToString(RegisterBody.serializer(), RegisterBody("ntfy", endpoint)),
        DeviceRegisterResponse.serializer(),
    )

    suspend fun updateDeviceToken(endpoint: String): Result<DeviceOpsResponse> =
        postJson("api/devices/${requiredDeviceId()}/token", endpointBody(endpoint), DeviceOpsResponse.serializer())

    suspend fun fetchEvent(eventId: String): Result<HermesEvent> = getJson(
        "api/events/${encoded(eventId)}", HermesEvent.serializer(),
    )

    suspend fun fetchPendingEvents(): Result<HermesEventsPage> = getJson(
        "api/events?status=pending&device_id=${encoded(requiredDeviceId())}", HermesEventsPage.serializer(),
    )

    suspend fun ackEvent(eventId: String): Result<DeviceOpsResponse> =
        postJson("api/events/${encoded(eventId)}/ack", "{}", DeviceOpsResponse.serializer())

    private fun endpointBody(endpoint: String): String =
        HermesJson.encodeToString(TokenBody.serializer(), TokenBody(endpoint))

    private fun requiredDeviceId(): String = deviceId?.takeIf { it.isNotBlank() }
        ?: throw IllegalStateException("A registered device_id is required")

    private suspend fun <T> getJson(path: String, serializer: KSerializer<T>): Result<T> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("$baseUrl/$path")
                .addHeader("Authorization", "Bearer $apiKey").get().build()
            Http.base.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                check(response.isSuccessful) { "HTTP ${response.code}: ${text.take(200).ifBlank { response.message }}" }
                HermesJson.decodeFromString(serializer, text)
            }
        }
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

    @Serializable private data class RegisterBody(val enc_type: String, val push_endpoint: String)
    @Serializable private data class TokenBody(val push_endpoint: String)

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        fun encoded(value: String) = URLEncoder.encode(value, "UTF-8")
    }
}
