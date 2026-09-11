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
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.ConversationStore
import org.junit.rules.TemporaryFolder

class SessionFirstTurnTest {
    @get:org.junit.Rule val temporaryFolder = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }
    private fun client() = HermesClient(server.url("/").toString().trimEnd('/'), "test-key")
    private fun response(id: String) = MockResponse().setResponseCode(201).setBody("{\"session\":{\"id\":\"$id\"}}")
    private fun title(request: RecordedRequest) = request.body.clone().readUtf8().substringAfter("\"title\":\"").substringBefore("\"}")

    @Test fun `firstTurn_modelLockFails_noTurnSent`() = runBlocking {
        server.enqueue(response("s1"))
        server.enqueue(MockResponse().setResponseCode(500))
        val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder()))
        r.queueFirstTurn("hello")
        val outcome = startSessionTurn(r, client(), server.url("/").toString().trimEnd('/'), "hello", "id", "model-x")
        assertTrue(outcome is SessionTurnStartOutcome.LockFailed)
        val paths = listOf(server.takeRequest().path, server.takeRequest().path)
        assertEquals(listOf("/api/sessions", "/api/sessions/s1/model"), paths)
        assertTrue(paths.none { it!!.contains("/chat") })
        assertEquals(1, r.messages.count { it.role == "user" })
    }

    @Test fun `firstTurn_modelLock4xx_isBlocked`() = runBlocking {
        server.enqueue(response("s1"))
        server.enqueue(MockResponse().setResponseCode(400))
        val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder()))
        r.queueFirstTurn("hello")
        val outcome = startSessionTurn(r, client(), server.url("/").toString().trimEnd('/'), "hello", "id", "model-x")
        assertTrue(outcome is SessionTurnStartOutcome.LockFailed)
        val paths = listOf(server.takeRequest().path, server.takeRequest().path)
        assertEquals(listOf("/api/sessions", "/api/sessions/s1/model"), paths)
        assertTrue(paths.none { it!!.contains("/chat") })
        assertTrue(r.messages.any { it.role == "user" && it.text == "hello" })
    }

    @Test fun `firstTurn_modelLockAck_allowsStart`() = runBlocking {
        server.enqueue(response("s1"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder()))
        val outcome = startSessionTurn(r, client(), server.url("/").toString().trimEnd('/'), "hello", "id", "model-x")
        assertTrue(outcome is SessionTurnStartOutcome.Started)
        assertEquals("s1", r.sessionId)
        // The caller sends the chat turn only after Started; this test intentionally does not.
        assertEquals("/api/sessions", server.takeRequest().path)
        assertEquals("/api/sessions/s1/model", server.takeRequest().path)
    }

    @Test fun `lock failure retrying same first turn keeps one user message`() = runBlocking {
        server.enqueue(response("s1"))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder()))
        r.queueFirstTurn("hello")
        assertTrue(startSessionTurn(r, client(), server.url("/").toString().trimEnd('/'), "hello", "id", "model-x") is SessionTurnStartOutcome.LockFailed)
        r.queueFirstTurn("hello")
        assertTrue(startSessionTurn(r, client(), server.url("/").toString().trimEnd('/'), "hello", "id", "model-x") is SessionTurnStartOutcome.Started)
        assertEquals(1, r.messages.count { it.role == "user" && !it.isError })
    }

    @Test fun `boundSession_retriesLockBeforeCallerTurn`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder()))
        r.bindSession("origin", "s1", ChatTransportKind.SESSIONS)
        assertTrue(startSessionTurn(r, client(), "origin", "ignored", "id", "model-x") is SessionTurnStartOutcome.Started)
        assertEquals("/api/sessions/s1/model", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

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
            val t = title(r); return if (!seen.add(t)) MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"code\":\"session_exists\",\"message\":\"duplicate title\"}}")
                else response("sid-${seen.size}")
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

    @Test fun `400 with different rpc code is not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("{\"error\":{\"code\":\"invalid_request\",\"message\":\"bad title\"}}"))
        assertTrue(createSessionForFirstTurn(client(), "x", "id").isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `409 without rpc code retries with suffix`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409))
        server.enqueue(response("s2"))
        assertEquals("s2", createSessionForFirstTurn(client(), "x", "abcdefghijk").getOrThrow())
        assertEquals(2, server.requestCount)
        assertEquals("x", title(server.takeRequest()))
        assertEquals("x · abcdefgh", title(server.takeRequest()))
    }

    @Test fun `500 is not retried`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        assertTrue(createSessionForFirstTurn(client(), "x", "id").isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `400 without rpc code is not retried`() = runBlocking {
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest) = MockResponse().setResponseCode(400) }
        assertTrue(createSessionForFirstTurn(client(), "x", "id").isFailure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `blank conversation title collision gets suffix`() = runBlocking {
        val seen = mutableSetOf<String>("Conversation")
        server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest): MockResponse {
            return if (!seen.add(title(r))) MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"code\":\"session_exists\"}}")
                else response("s${seen.size}")
        } }
        createSessionForFirstTurn(client(), "Conversation", "123456789").getOrThrow()
        assertEquals(2, server.requestCount)
        server.takeRequest(); server.takeRequest()
        assertTrue(seen.any { it.startsWith("Conversation · ") })
    }
}
