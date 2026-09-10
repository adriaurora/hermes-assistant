package dk.foss.jarvis.hermes

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class SessionFirstTurnTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }
    private fun client() = HermesClient(server.url("/").toString().trimEnd('/'), "test-key")
    private fun response(id: String) = MockResponse().setResponseCode(201).setBody("{\"session\":{\"id\":\"$id\"}}")
    private fun title(request: RecordedRequest) = request.body.clone().readUtf8().substringAfter("\"title\":\"").substringBefore("\"}")

    @Test fun `null title falls back`() { assertEquals("Conversation", sessionTitleFrom(null)) }
    @Test fun `blank title falls back`() { assertEquals("Conversation", sessionTitleFrom("")); assertEquals("Conversation", sessionTitleFrom("   \n\t ")) }
    @Test fun `title collapses whitespace`() { assertEquals("hello world", sessionTitleFrom("hello\n\n  world")) }
    @Test fun `title is capped`() { assertEquals(60, sessionTitleFrom("x".repeat(200)).length) }
    @Test fun `normal title is preserved`() { assertEquals("Hello there", sessionTitleFrom("Hello there")) }

    @Test fun `successful create posts once`() = runBlocking {
        server.enqueue(response("s1"))
        assertEquals("s1", createSessionForFirstTurn(client(), "hello world", "conversation").getOrThrow())
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("/api/sessions", request.path); assertEquals("hello world", title(request))
        assertNull(server.takeRequest(100, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test fun `duplicate title retries with suffix`() = runBlocking {
        val seen = ConcurrentHashMap.newKeySet<String>()
        seen += "same"
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest): MockResponse {
            val t = title(r); return if (!seen.add(t)) MockResponse().setResponseCode(400) else response("sid-${seen.size}")
        } }
        assertEquals("sid-2", createSessionForFirstTurn(client(), "same", "abcdefghijk").getOrThrow())
        val a = server.takeRequest(); val b = server.takeRequest()
        assertEquals("same", title(a)); assertTrue(title(b).startsWith("same · ")); assertEquals(2, seen.size)
    }

    @Test fun `consecutive same titles get distinct ids`() = runBlocking {
        server.enqueue(response("one")); server.enqueue(response("two"))
        val a = createSessionForFirstTurn(client(), "same", "a").getOrThrow()
        val b = createSessionForFirstTurn(client(), "same", "b").getOrThrow()
        assertNotEquals(a, b); assertEquals(2, server.requestCount)
    }

    @Test fun `transient failure is not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(createSessionForFirstTurn(client(), "x", "id").isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `validation failure retries only once`() = runBlocking {
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setResponseCode(400) }
        assertTrue(createSessionForFirstTurn(client(), "x", "id").isFailure)
        assertEquals(2, server.requestCount)
    }

    @Test fun `blank conversation title collision gets suffix`() = runBlocking {
        val seen = mutableSetOf<String>("Conversation")
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest): MockResponse {
            return if (!seen.add(title(r))) MockResponse().setResponseCode(400) else response("s${seen.size}")
        } }
        createSessionForFirstTurn(client(), "Conversation", "123456789").getOrThrow()
        assertEquals(2, server.requestCount)
        server.takeRequest(); server.takeRequest()
        assertTrue(seen.any { it.startsWith("Conversation · ") })
    }
}
