package dk.foss.jarvis.hermes

import dk.foss.jarvis.hermes.FetchFailureKind
import dk.foss.jarvis.push.RpcErrorClass
import dk.foss.jarvis.push.RpcRetryPolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking
import java.net.SocketException

class EventRpcClientTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(apiKey: String = "KEY-XYZ", deviceId: String? = null, deviceSecret: String? = null): EventRpcClient =
        EventRpcClient(server.url("/").toString().trimEnd('/'), apiKey, deviceId, deviceSecret)

    // 1. register nuevo
    @Test fun `register nuevo responde success con 4 campos`() = runBlocking {
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
        assertEquals("active", r.state)
        assertEquals(false, r.existing)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertTrue(request.path!!.endsWith(RPC_PATH))
        assertEquals("Bearer KEY-XYZ", request.getHeader("Authorization"))
        val bodyStr = request.body.readUtf8()
        assertTrue(bodyStr.contains("\"type\":\"device.register\""))
        assertTrue(bodyStr.contains("\"push\":{\"type\":\"fcm\""))
    }

    // 2. register idempotente con creds
    @Test fun `register idempotente con creds success`() = runBlocking {
        val responseBody = """{
            "ok":true,"protocol_version":1,"result":{
                "device_id":"d-1","device_secret":null,
                "state":"active","existing":true
            }
        }""".trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        val result = client(deviceId = "d-1", deviceSecret = "s-1").register("my-device", "push-token-123", "d-1", "s-1")
        assertTrue(result.isSuccess)
        val r = result.getOrThrow()
        assertEquals("d-1", r.device_id)
        assertNull(r.device_secret)
        assertEquals(true, r.existing)
    }

    // 3. updateToken con creds y sin creds
    @Test fun `updateToken con creds`() = runBlocking {
        val responseBody = """{
            "ok":true,"protocol_version":1,"result":{"device_id":"d-1","state":"active"}
        }""".trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        val result = client(deviceId = "d-1", deviceSecret = "s-1").updateToken("new-push-token")
        assertTrue(result.isSuccess)

        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue(bodyStr.contains("\"type\":\"device.token.update\""))
        assertTrue(bodyStr.contains("\"device_id\":\"d-1\""))
        assertTrue(bodyStr.contains("\"device_secret\":\"s-1\""))
        assertTrue(bodyStr.contains("\"push_token\":\"new-push-token\""))
    }

    @Test fun `updateToken sin creds retorna failure sin llamar servidor`() = runBlocking {
        val clientNoCreds = client() // no deviceId, no deviceSecret
        val result = clientNoCreds.updateToken("some-token")
        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    // 4. revoke
    @Test fun `revoke success`() = runBlocking {
        val responseBody = """{
            "ok":true,"protocol_version":1,"result":{"device_id":"d-1","state":"revoked"}
        }""".trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        val result = client(deviceId = "d-1", deviceSecret = "s-1").revoke()
        assertTrue(result.isSuccess)

        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue(bodyStr.contains("\"type\":\"device.revoke\""))
        assertTrue(bodyStr.contains("\"device_id\":\"d-1\""))
        assertTrue(bodyStr.contains("\"device_secret\":\"s-1\""))
        // revoke body MUST NOT contain push_token
        assertTrue(bodyStr.contains("\"push_token\"").not())
    }

    // 5. fetchEvent
    @Test fun `fetchEvent success`() = runBlocking {
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
        assertEquals(2, event.priority)
        assertEquals("delivered", event.state)
    }

    @Test fun `fetchEvent event_id distinto genera failure`() = runBlocking {
        val responseBody = """{
            "ok":true,"protocol_version":1,"result":{
                "event_id":"different-id","event_type":"reminder","created_at":1.0,
                "available_at":2.0
            }
        }""".trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        val result = client().fetchEvent("123e4567-e89b-12d3-a456-426614174000")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as? EventFetchException
        assertEquals(FetchFailureKind.SERIALIZATION, ex?.kind)
    }

    @Test fun `fetchEvent con id no-UUID no hit servidor`() = runBlocking {
        val result = client().fetchEvent("not-a-uuid")
        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
    }

    // 6. ack
    @Test fun `ack success with ok true`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"protocol_version\":1,\"result\":{}}"))
        val result = client().ack("123e4567-e89b-12d3-a456-426614174000")
        assertTrue(result.isSuccess)
    }

    @Test fun `ack event_not_found retorna success`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":false,\"error\":{\"code\":\"event_not_found\",\"http_status\":404}}"))
        val result = client().ack("123e4567-e89b-12d3-a456-426614174000")
        assertTrue(result.isSuccess)
    }

    @Test fun `ack device_auth_failed retorna failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":false,\"error\":{\"code\":\"device_auth_failed\",\"http_status\":403}}"))
        val result = client().ack("123e4567-e89b-12d3-a456-426614174000")
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals("device_auth_failed", ex.rpcCode)
    }

    // 7. pending
    @Test fun `pending success con 2 eventos`() = runBlocking {
        val responseBody = """{
            "ok":true,"protocol_version":1,"result":{
                "events":[
                    {"event_id":"e-1","event_type":"reminder","created_at":1.0,"available_at":2.0},
                    {"event_id":"e-2","event_type":"alarm","created_at":3.0,"available_at":4.0}
                ]
            }
        }""".trimIndent()
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))
        val result = client().pending()
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().events.size)
    }

    @Test fun `pending body type events pending con limit 50`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"protocol_version\":1,\"result\":{\"events\":[]}}"))
        client().pending()
        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue(bodyStr.contains("\"type\":\"events.pending\""))
        assertTrue(bodyStr.contains("\"limit\":50"))
    }

    @Test fun `pending(150) clamp a 100`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"protocol_version\":1,\"result\":{\"events\":[]}}"))
        client().pending(150)
        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue(bodyStr.contains("\"limit\":100"))
    }

    // 8. device_auth_failed → failure NUNCA success
    @Test fun `device_auth_failed failure con RpcLogicError`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"ok":false,"error":{"code":"device_auth_failed","message":"Invalid device credentials","http_status":403}}"""
        ))
        val result = client().pending()
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals(FetchFailureKind.HTTP, ex.kind)
        assertEquals("device_auth_failed", ex.rpcCode)
        assertEquals(403, ex.statusCode)
        // Verify it's classified as REENROLL by policy
        assertEquals(RpcErrorClass.REENROLL, RpcRetryPolicy.classify(ex.kind, ex.statusCode, ex.rpcCode))
    }

    // 9. ok:false SIN error object
    @Test fun `ok false sin error object`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":false}"))
        val result = client().pending()
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals("invalid_response", ex.rpcCode)
    }

    // 10. protocol_version:2
    @Test fun `protocol version 2 failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"ok\":true,\"protocol_version\":2,\"result\":{}}"))
        val result = client().pending()
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals("unsupported_protocol", ex.rpcCode)
    }

    // 11. HTTP 401 sin envelope
    @Test fun `HTTP 401 sin envelope`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client().pending()
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals(FetchFailureKind.HTTP, ex.kind)
        assertEquals(401, ex.statusCode)
        assertNull(ex.rpcCode)
    }

    // 12. HTTP 503
    @Test fun `HTTP 503`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = client().pending()
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals(FetchFailureKind.HTTP, ex.kind)
        assertEquals(503, ex.statusCode)
        assertEquals(RpcErrorClass.RETRY, RpcRetryPolicy.classify(ex.kind, ex.statusCode, ex.rpcCode))
    }

    // 13. HTTP 404
    @Test fun `HTTP 404 sin envelope`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = client().pending()
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals(FetchFailureKind.HTTP, ex.kind)
        assertEquals(404, ex.statusCode)
    }

    // 14. Body HTML/malformado con 200
    @Test fun `body HTML con 200 failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>error</html>"))
        val result = client().pending()
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals(FetchFailureKind.SERIALIZATION, ex.kind)
    }

    // 15. Network failure
    @Test fun `network failure`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = client().pending()
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull() as EventFetchException
        assertEquals(FetchFailureKind.NETWORK, ex.kind)
    }

    // 16. probe
    @Test fun `probe success 200`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = client().probe()
        assertTrue(result.isSuccess)
        assertEquals(200, result.getOrThrow())

        val bodyStr = server.takeRequest().body.readUtf8()
        assertTrue(bodyStr.contains("\"type\":\"events.pending\""))
        assertTrue(bodyStr.contains("\"device_id\":\"00000000-0000-0000-0000-000000000000\""))
        assertTrue(bodyStr.contains("\"device_secret\":\"capability-probe\""))
    }

    @Test fun `probe success 401`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        val result = client().probe()
        assertTrue(result.isSuccess)
        assertEquals(401, result.getOrThrow())
    }

    @Test fun `probe success 404`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        val result = client().probe()
        assertTrue(result.isSuccess)
        assertEquals(404, result.getOrThrow())
    }

    @Test fun `probe success 503`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        val result = client().probe()
        assertTrue(result.isSuccess)
        assertEquals(503, result.getOrThrow())
    }

    @Test fun `probe network down failure`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = client().probe()
        assertTrue(result.isFailure)
    }

    // 17. Secret filtering - las exceptions NO deben contener secretos
    @Test fun `secrets no aparecen en las exceptions`() = runBlocking {
        val apiKey = "KEY-XYZ"
        val deviceSecret = "SECRET-XYZ"

        // Test register failure
        server.enqueue(MockResponse().setResponseCode(500).setBody("{\"error\":\"server error\"}"))
        val r1 = client(apiKey = apiKey, deviceId = "d-1", deviceSecret = deviceSecret).register("dev", "token")
        assertTrue(r1.isFailure)
        checkNoSecretInException(r1.exceptionOrNull(), deviceSecret, apiKey)

        // Test fetchEvent failure (bad status)
        server.enqueue(MockResponse().setResponseCode(502))
        val r2 = client(apiKey = apiKey).fetchEvent("123e4567-e89b-12d3-a456-426614174000")
        assertTrue(r2.isFailure)
        checkNoSecretInException(r2.exceptionOrNull(), deviceSecret, apiKey)

        // Test ack failure
        server.enqueue(MockResponse().setResponseCode(403))
        val r3 = client(apiKey = apiKey, deviceId = "d-1", deviceSecret = deviceSecret).ack("123e4567-e89b-12d3-a456-426614174000")
        assertTrue(r3.isFailure)
        checkNoSecretInException(r3.exceptionOrNull(), deviceSecret, apiKey)

        // Test pending failure
        server.enqueue(MockResponse().setResponseCode(401))
        val r4 = client(apiKey = apiKey).pending()
        assertTrue(r4.isFailure)
        checkNoSecretInException(r4.exceptionOrNull(), deviceSecret, apiKey)
    }

    private fun checkNoSecretInException(ex: Throwable?, deviceSecret: String, apiKey: String) {
        var current: Throwable? = ex
        while (current != null) {
            val msg = current.message.orEmpty()
            assertTrue("message should not contain deviceSecret: $msg", msg.contains(deviceSecret).not())
            assertTrue("message should not contain apiKey: $msg", msg.contains(apiKey).not())
            current = current.cause
        }
    }
}