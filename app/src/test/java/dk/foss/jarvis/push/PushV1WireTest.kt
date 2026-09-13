package dk.foss.jarvis.push

import dk.foss.jarvis.hermes.EventRpcClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Verifies that V1 RPC push operations work correctly and that
 * no legacy EventClient requests are ever made.
 */
class PushV1WireTest {

    private val server = MockWebServer()

    @Before fun setUp() { server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun client(apiKey: String = "KEY-XYZ"): EventRpcClient =
        EventRpcClient(server.url("/").toString().trimEnd('/'), apiKey, "dev-1", "secret-1")

    @Test
    fun `register sends V1 RPC envelope`() = runBlocking {
        val responseBody = """{
            "ok":true,"protocol_version":1,"result":{
                "device_id":"d-1","device_secret":"s-1",
                "state":"active","existing":false
            }
        }""".trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        val result = client().register("my-device", "push-token-123")
        assertTrue(result.isSuccess)
        val r = result.getOrThrow()
        assertEquals("d-1", r.device_id)
        assertEquals("s-1", r.device_secret)
        assertEquals(false, r.existing)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith("api/platforms/hermes_assistant/events"))
        val bodyStr = request.body.readUtf8()
        assertTrue(bodyStr.contains("\"type\":\"device.register\""))
        assertTrue(bodyStr.contains("\"protocol_version\":1"))
        // Verify NO legacy path
        assertTrue(bodyStr.contains("api/devices").not())
        assertTrue(bodyStr.contains("api/events").not())
    }

    @Test
    fun `revoke sends V1 RPC envelope`() = runBlocking {
        val responseBody = """{
            "ok":true,"protocol_version":1,"result":{"device_id":"d-1","state":"revoked"}
        }""".trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        val result = client().revoke()
        assertTrue(result.isSuccess)

        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue(bodyStr.contains("\"type\":\"device.revoke\""))
        assertTrue(bodyStr.contains("\"device_id\":\"dev-1\""))
        assertTrue(bodyStr.contains("\"device_secret\":\"secret-1\""))
        // Verify NO legacy path
        assertTrue(bodyStr.contains("api/devices").not())
        assertTrue(bodyStr.contains("api/events").not())
    }

    @Test
    fun `fetchEvent sends V1 RPC envelope`() = runBlocking {
        val eventId = "123e4567-e89b-12d3-a456-426614174000"
        val responseBody = """{
            "ok":true,"protocol_version":1,"result":{
                "event_id":"$eventId","event_type":"reminder","title":"t","body":"b",
                "priority":"high","source":"cron","source_id":"1","session_id":"s",
                "state":"delivered","created_at":1.0,"available_at":1.0,
                "expires_at":null,"delivered_at":2.0,"acknowledged_at":null
            }
        }""".trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        val result = client().fetchEvent(eventId)
        assertTrue(result.isSuccess)
        val event = result.getOrThrow()
        assertEquals(eventId, event.event_id)

        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue(bodyStr.contains("\"type\":\"event.get\""))
        assertTrue(bodyStr.contains("\"device_secret\":\"secret-1\""))
        // Verify NO legacy path
        assertTrue(bodyStr.contains("api/devices").not())
        assertTrue(bodyStr.contains("api/events").not())
    }

    @Test
    fun `ack sends V1 RPC envelope`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"protocol_version\":1,\"result\":{}}"))
        val result = client().ack("123e4567-e89b-12d3-a456-426614174000")
        assertTrue(result.isSuccess)

        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue(bodyStr.contains("\"type\":\"event.ack\""))
        // Verify NO legacy path
        assertTrue(bodyStr.contains("api/devices").not())
        assertTrue(bodyStr.contains("api/events").not())
    }

    @Test
    fun `pending sends V1 RPC envelope`() = runBlocking {
        val responseBody = """{
            "ok":true,"protocol_version":1,"result":{
                "events":[
                    {"event_id":"e-1","event_type":"reminder","created_at":1.0,"available_at":2.0}
                ]
            }
        }""".trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        val result = client().pending()
        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrThrow().events.size)

        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue(bodyStr.contains("\"type\":\"events.pending\""))
        // Verify NO legacy path
        assertTrue(bodyStr.contains("api/devices").not())
        assertTrue(bodyStr.contains("api/events").not())
    }

    @Test
    fun `probe sends V1 RPC envelope`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = client().probe()
        assertTrue(result.isSuccess)
        assertEquals(200, result.getOrThrow())

        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue(bodyStr.contains("\"type\":\"events.pending\""))
        assertTrue(bodyStr.contains("\"device_secret\":\"capability-probe\""))
    }
}