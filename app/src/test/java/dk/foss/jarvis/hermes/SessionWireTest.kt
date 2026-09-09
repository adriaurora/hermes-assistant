package dk.foss.jarvis.hermes

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * Tests session wire operations: createSession, sendSessionTurn, streamSessionTurn,
 * error parsing, and body contracts.
 */
class SessionWireTest {

    private val server = MockWebServer()

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(): HermesClient =
        HermesClient(server.url("/").toString().trimEnd('/'), "test-key")

    // 1. createSession with full envelope
    @Test
    fun `createSession returns session id from envelope`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"object":"hermes.session","session":{"id":"s1","model":null}}"""))
        val result = client().createSession("x")
        assertTrue(result.isSuccess)
        assertEquals("s1", result.getOrThrow())
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/api/sessions", req.path)
        val body = req.body.readUtf8()
        assertEquals("""{"title":"x"}""", body)
        assertEquals("Bearer test-key", req.getHeader("Authorization"))
    }

    // 1b. createSession body exact check
    @Test
    fun `createSession body exact title`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"object":"hermes.session","session":{"id":"s1","model":null}}"""))
        client().createSession("x")
        val body = server.takeRequest().body.readUtf8()
        assertEquals("""{"title":"x"}""", body)
    }

    // 2. Flat fallback: {"id":"s2"} → "s2"
    @Test
    fun `createSession flat fallback returns id`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"id":"s2"}"""))
        val result = client().createSession("test")
        assertTrue(result.isSuccess)
        assertEquals("s2", result.getOrThrow())
    }

    // 3. streamSessionTurn: path, body, headers
    @Test
    fun `streamSessionTurn uses correct path and headers`() = runBlocking {
        // Minimal SSE body — the EventSource listener will process it but we mainly check request
        val sseBody = "event: done\ndata: \n\n"
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sseBody))

        val cb = object : HermesClient.StreamCallbacks {
            var delta = ""
            override fun onDelta(textDelta: String) { delta += textDelta }
            var completed = false
            override fun onComplete() { completed = true }
        }
        client().streamSessionTurn("s1", "hi", cb)
        // Let SSE processing happen
        Thread.sleep(200)

        val req = server.takeRequest()
        assertEquals("/api/sessions/s1/chat/stream", req.path)
        val body = req.body.readUtf8()
        assertEquals("""{"message":"hi"}""", body)
        assertFalse(body.contains("model"))
        assertFalse(body.contains("provider"))
        assertEquals("text/event-stream", req.getHeader("Accept"))
        assertEquals("Bearer test-key", req.getHeader("Authorization"))
        assertNull(req.getHeader("X-Hermes-Session-Id"))
    }

    // 4. sendSessionTurn non-stream
    @Test
    fun `sendSessionTurn non-stream returns text and runtime`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"content":"ok","runtime":{"model":"m","route_source":"session_model_lock"}}"""))
        val result = client().sendSessionTurn("s1", "hi")
        assertTrue(result.isSuccess)
        val turnResult = result.getOrThrow()
        assertEquals("ok", turnResult.text)
        assertEquals("m", turnResult.runtime?.model)
        assertEquals("session_model_lock", turnResult.runtime?.route_source)

        val req = server.takeRequest()
        assertEquals("/api/sessions/s1/chat", req.path)
        assertEquals("""{"message":"hi"}""", req.body.readUtf8())
    }

    // 5. Non-stream with no recognizable text field → failure
    @Test
    fun `sendSessionTurn no text field returns failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"foo":1}"""))
        val result = client().sendSessionTurn("s1", "hi")
        assertTrue(result.isFailure)
    }

    // 6. 404 with session_not_found → isSessionMissing
    @Test
    fun `404 session_not_found yields isSessionMissing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404)
            .setBody("""{"error":{"code":"session_not_found","message":"gone"}}"""))
        val result = client().sendSessionTurn("s1", "hi")
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as? HermesHttpError
        assertNotNull(err)
        assertEquals(404, err?.code)
        assertTrue(err?.isSessionMissing ?: false)
    }

    // 7. 401 with gateway_auth_failed → isAuth
    @Test
    fun `401 gateway_auth_failed yields isAuth`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"error":{"code":"gateway_auth_failed"}}"""))
        val result = client().sendSessionTurn("s1", "hi")
        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as? HermesHttpError
        assertNotNull(err)
        assertEquals(401, err?.code)
        assertTrue(err?.isAuth ?: false)
    }

    // 8. parseErrorBody: {"error":{"code":"c","message":"m"}}
    @Test
    fun `parseErrorBody error object with code and message`() {
        val (code, msg) = parseErrorBody("""{"error":{"code":"c","message":"m"}}""")
        assertEquals("c", code)
        assertEquals("m", msg)
    }

    // 9. parseErrorBody: {"error":"e"}
    @Test
    fun `parseErrorBody error string`() {
        val (code, msg) = parseErrorBody("""{"error":"e"}""")
        assertNull(code)
        assertEquals("e", msg)
    }

    // 10. parseErrorBody: {"detail":"d"}
    @Test
    fun `parseErrorBody detail field`() {
        val (code, msg) = parseErrorBody("""{"detail":"d"}""")
        assertNull(code)
        assertEquals("d", msg)
    }

    // 11. parseErrorBody: {"code":"c"}
    @Test
    fun `parseErrorBody code only`() {
        val (code, msg) = parseErrorBody("""{"code":"c"}""")
        assertEquals("c", code)
        assertNull(msg)
    }

    // 12. parseErrorBody: plain text → (null, excerpt)
    @Test
    fun `parseErrorBody plain text`() {
        val (code, msg) = parseErrorBody("plain text response")
        assertNull(code)
        assertNotNull(msg)
        assertTrue(msg!!.contains("plain text"))
    }
}