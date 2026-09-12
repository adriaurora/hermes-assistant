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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.ConversationStore
import dk.foss.jarvis.data.PendingModelIntent
import org.junit.rules.TemporaryFolder

class SessionFirstTurnTest {
    @get:org.junit.Rule val temporaryFolder = TemporaryFolder()
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }
    private fun client() = HermesClient(server.url("/").toString().trimEnd('/'), "test-key")
    private fun response(id: String) = MockResponse().setResponseCode(201).setBody("{\"session\":{\"id\":\"$id\"}}")
    private fun title(request: RecordedRequest) = request.body.clone().readUtf8().substringAfter("\"title\":\"").substringBefore("\"}")
    private fun stream(request: RecordedRequest) {
        // Boundary equivalent of the caller's chat/stream after a Started outcome.
        assertEquals("/api/sessions/s1/chat/stream", request.path)
    }
    private fun sendStream(c: HermesClient, sid: String) {
        val done = CountDownLatch(1)
        c.streamSessionTurn(sid, "hello", object : HermesClient.StreamCallbacks {
            override fun onDelta(textDelta: String) = Unit
            override fun onFinalContent(text: String) = Unit
            override fun onToolProgress(tool: String, label: String?, running: Boolean) = Unit
            override fun onRuntime(info: RuntimeInfo) = Unit
            override fun onComplete() { done.countDown() }
            override fun onError(message: String) { done.countDown() }
        })
        assertTrue(done.await(3, TimeUnit.SECONDS))
    }

    @Test fun `firstTurn_modelLockFails_noTurnSent`() = runBlocking {
        server.enqueue(response("s1"))
        server.enqueue(MockResponse().setResponseCode(500))
        val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder()))
        r.queueFirstTurn("hello")
        val outcome = startSessionTurn(r, client(), server.url("/").toString().trimEnd('/'), "hello", "id", PendingModelIntent.Set("model-x", "model-x"))
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
        val outcome = startSessionTurn(r, client(), server.url("/").toString().trimEnd('/'), "hello", "id", PendingModelIntent.Set("model-x", "model-x"))
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
        val outcome = startSessionTurn(r, client(), server.url("/").toString().trimEnd('/'), "hello", "id", PendingModelIntent.Set("model-x", "model-x"))
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
        assertTrue(startSessionTurn(r, client(), server.url("/").toString().trimEnd('/'), "hello", "id", PendingModelIntent.Set("model-x", "model-x")) is SessionTurnStartOutcome.LockFailed)
        r.queueFirstTurn("hello")
        assertTrue(startSessionTurn(r, client(), server.url("/").toString().trimEnd('/'), "hello", "id", PendingModelIntent.Set("model-x", "model-x")) is SessionTurnStartOutcome.Started)
        assertEquals(1, r.messages.count { it.role == "user" && !it.isError })
    }

    @Test fun `boundSession_retriesLockBeforeCallerTurn`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder()))
        r.bindSession("origin", "s1", ChatTransportKind.SESSIONS)
        assertTrue(startSessionTurn(r, client(), "origin", "ignored", "id", PendingModelIntent.Set("model-x", "model-x")) is SessionTurnStartOutcome.Started)
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
        assertEquals(2, server.requestCount)
    }

    @Test fun `invalidTitle400_retriesWithSuffix_then201`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("{\"error\":{\"code\":\"invalid_title\",\"message\":\"Title already in use by session X\"}}"))
        server.enqueue(response("sid-201"))

        val result = createSessionForFirstTurn(client(), "same", "abcdefghijk")

        assertEquals("sid-201", result.getOrThrow())
        assertEquals(2, server.requestCount)
        assertEquals("same", title(server.takeRequest()))
        assertEquals("same · abcdefgh", title(server.takeRequest()))
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
        val result = createSessionForFirstTurn(client(), "x", "id")
        assertTrue(result.isFailure)
        assertEquals("invalid_request", (result.exceptionOrNull() as HermesHttpError).rpcCode)
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

    // T1: a failed A remains pending; an acknowledged B replaces it and retry streams.
    @Test fun case1_lockAfails_pickB_ack_retry_usesB_neverResendsA() = runBlocking {
        server.enqueue(response("s1")); server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: {}\n\n"))
        val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder())); val c = client()
        r.queueFirstTurn("hello"); r.pendingModelIntent = PendingModelIntent.Set("A", "A")
        assertTrue(startSessionTurn(r, c, "o", "hello", "id", r.pendingModelIntent) is SessionTurnStartOutcome.LockFailed)
        assertEquals(PendingModelIntent.Set("A", "A"), r.pendingModelIntent)
        r.pendingModelIntent = PendingModelIntent.Set("B", "B"); c.setSessionModel("s1", "B").getOrThrow(); r.consumePendingModelIntent(PendingModelIntent.Set("B", "B"))
        assertTrue(startSessionTurn(r, c, "o", "hello", "id", null) is SessionTurnStartOutcome.Started); sendStream(c, "s1")
        val requests = listOf(server.takeRequest(), server.takeRequest(), server.takeRequest(), server.takeRequest())
        assertEquals(listOf("/api/sessions", "/api/sessions/s1/model", "/api/sessions/s1/model", "/api/sessions/s1/chat/stream"), requests.map { it.path })
        assertEquals("{\"model\":\"A\"}", requests[1].body.readUtf8()); assertEquals("{\"model\":\"B\"}", requests[2].body.readUtf8())
    }

    // T2: Automatic is a distinct clear intent and must not resurrect A.
    @Test fun case1_lockAfails_pickAutomatic_clearAck_retry_global() = runBlocking {
        server.enqueue(response("s1")); server.enqueue(MockResponse().setResponseCode(500)); server.enqueue(MockResponse().setResponseCode(200).setBody("{}")); server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: {}\n\n"))
        val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder())); val c = client(); r.queueFirstTurn("hello"); r.pendingModelIntent = PendingModelIntent.Set("A", "A")
        assertTrue(startSessionTurn(r, c, "o", "hello", "id", r.pendingModelIntent) is SessionTurnStartOutcome.LockFailed)
        r.pendingModelIntent = PendingModelIntent.Clear; c.clearSessionModel("s1").getOrThrow(); r.consumePendingModelIntent(PendingModelIntent.Clear)
        assertTrue(startSessionTurn(r, c, "o", "hello", "id", null) is SessionTurnStartOutcome.Started); sendStream(c, "s1")
        val requests = listOf(server.takeRequest(), server.takeRequest(), server.takeRequest(), server.takeRequest())
        assertEquals(listOf("/api/sessions", "/api/sessions/s1/model", "/api/sessions/s1/model", "/api/sessions/s1/chat/stream"), requests.map { it.path })
        assertEquals("{\"model\":\"A\"}", requests[1].body.readUtf8()); assertEquals("{\"model\":null}", requests[2].body.readUtf8()); assertNull(r.pendingModelIntent)
    }

    // T3/T4: voice's first turn crosses the same boundary, including fail-closed locking.
    @Test fun case2_freshIntentA_voiceTurn_locksA_thenStreams() = runBlocking {
        server.enqueue(response("s1")); server.enqueue(MockResponse().setResponseCode(200).setBody("{}")); server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: {}\n\n"))
        val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder())); val c = client(); r.pendingModelIntent = PendingModelIntent.Set("A", "A")
        assertTrue(startSessionTurn(r, c, "o", "hello", "id", r.pendingModelIntent) is SessionTurnStartOutcome.Started); assertNull(r.pendingModelIntent); sendStream(c, "s1")
        val requests = listOf(server.takeRequest(), server.takeRequest(), server.takeRequest()); assertEquals(listOf("/api/sessions", "/api/sessions/s1/model", "/api/sessions/s1/chat/stream"), requests.map { it.path }); assertEquals("{\"model\":\"A\"}", requests[1].body.readUtf8())
    }

    @Test fun case2_freshIntentA_voice_lockFails_zeroTurns() = runBlocking {
        server.enqueue(response("s1")); server.enqueue(MockResponse().setResponseCode(500)); val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder())); r.pendingModelIntent = PendingModelIntent.Set("A", "A")
        assertTrue(startSessionTurn(r, client(), "o", "hello", "id", r.pendingModelIntent) is SessionTurnStartOutcome.LockFailed); assertEquals(2, server.requestCount); assertTrue(r.pendingModelIntent is PendingModelIntent.Set)
    }

    @Test fun clearIntent_onFreshSession_consumedWithoutPost() = runBlocking {
        server.enqueue(response("s1")); server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: {}\n\n")); val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder())); r.pendingModelIntent = PendingModelIntent.Clear
        assertTrue(startSessionTurn(r, client(), "o", "hello", "id", r.pendingModelIntent) is SessionTurnStartOutcome.Started); assertNull(r.pendingModelIntent); sendStream(client(), "s1"); assertEquals("/api/sessions", server.takeRequest().path); assertEquals("/api/sessions/s1/chat/stream", server.takeRequest().path)
    }

    @Test fun clearIntent_onExistingSession_clearsModelBeforeTurn() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}")); server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody("event: done\ndata: {}\n\n")); val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder())); r.bindSession("o", "s1", ChatTransportKind.SESSIONS); r.pendingModelIntent = PendingModelIntent.Clear
        assertTrue(startSessionTurn(r, client(), "o", "hello", "id", r.pendingModelIntent) is SessionTurnStartOutcome.Started); assertNull(r.pendingModelIntent); sendStream(client(), "s1"); val req = server.takeRequest(); assertEquals("/api/sessions/s1/model", req.path); assertEquals("{\"model\":null}", req.body.readUtf8()); assertEquals("/api/sessions/s1/chat/stream", server.takeRequest().path)
    }

    @Test fun intentReset_onConversationSwitch() = runBlocking {
        val r = ConversationRepository(ConversationStore(temporaryFolder.newFolder())); r.pendingModelIntent = PendingModelIntent.Set("A", "A"); r.startNew(); assertNull(r.pendingModelIntent)
        r.addMessage("user", "other"); r.persist(); val id = r.activeConversationId; r.pendingModelIntent = PendingModelIntent.Set("B", "B"); r.startNew(); r.open(id); assertNull(r.pendingModelIntent)
    }
}
