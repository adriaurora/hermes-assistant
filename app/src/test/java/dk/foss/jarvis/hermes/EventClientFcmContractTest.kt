package dk.foss.jarvis.hermes

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EventClientFcmContractTest {
    @Test fun registerUsesFcmPushTypeAndToken() {
        val body = Json.parseToJsonElement(EventClient.fcmRegisterBody("opaque")).jsonObject
        assertEquals("fcm", body["push_type"]?.jsonPrimitive?.content)
        assertEquals("opaque", body["push_token"]?.jsonPrimitive?.content)
        // device_id omitted when null
        assertNull(body["device_id"])
    }

    @Test fun registerIncludesDeviceIdWhenProvided() {
        val body = Json.parseToJsonElement(EventClient.fcmRegisterBody("opaque", "dev-123")).jsonObject
        assertEquals("fcm", body["push_type"]?.jsonPrimitive?.content)
        assertEquals("opaque", body["push_token"]?.jsonPrimitive?.content)
        assertEquals("dev-123", body["device_id"]?.jsonPrimitive?.content)
    }

    @Test fun refreshUsesFcmPushTypeAndToken() {
        val body = Json.parseToJsonElement(EventClient.fcmTokenBody("rotated")).jsonObject
        assertEquals("fcm", body["push_type"]?.jsonPrimitive?.content)
        assertEquals("rotated", body["push_token"]?.jsonPrimitive?.content)
    }
}
