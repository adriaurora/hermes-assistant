package dk.foss.jarvis.hermes

import dk.foss.jarvis.data.ConversationRepository
import dk.foss.jarvis.data.ConversationStore
import dk.foss.jarvis.data.UiMessage
import dk.foss.jarvis.ui.ModelSelection
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SessionsMockE2eTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val originA = MockWebServer()
    private val originB = MockWebServer()

    @Before fun start() { originA.start(); originB.start() }
    @After fun stop() { originA.shutdown(); originB.shutdown() }

    private fun base(server: MockWebServer) = server.url("/").toString().trimEnd('/')
    private fun client() = HermesClient(base(originA), "test-key")
    private fun sse(vararg events: Pair<String, String>) = events.joinToString("") { "event: ${it.first}\ndata: ${it.second}\n\n" }

    @Test
    fun `modern sessions flow discovers creates streams locks clears resumes and imports history`() = runBlocking {
        val recorded = mutableListOf<RecordedRequest>()
        val bodies = mutableMapOf<RecordedRequest, String>()
        fun request() = originA.takeRequest(5, TimeUnit.SECONDS)!!.also { recorded += it; bodies[it] = it.body.readUtf8() }
        fun body(request: RecordedRequest) = bodies[request].orEmpty()
        // Unknown fields are deliberately included: clients must tolerate newer
        // capability flags without losing the known session capabilities.
        val capabilities = """{"object":"hermes.api_server.capabilities","platform":"hermes-agent","future_top_level":true,"features":{"session_chat":true,"session_chat_streaming":true,"session_model_lock":true,"session_model_clear":true,"model_options":true,"chat_completions":true,"future_feature":"new"}}"""
        originA.enqueue(MockResponse().setResponseCode(200).setBody(capabilities))
        val features = client().getCapabilities().getOrThrow()
        val capRequest = request()
        assertEquals("/v1/capabilities", capRequest.path)
        assertTrue(features.session_chat && features.session_chat_streaming && features.session_model_lock)
        assertTrue(features.session_model_clear && features.model_options && features.chat_completions)

        val a = base(originA)
        assertTrue(ChatTransportSelector.decide(OriginCapabilities(a, CapabilityState.SUPPORTED, features), null, null, a, false) is ChatTransportDecision.Sessions)

        originA.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"hermes.session","session":{"id":"sess_e2e_1","model":null}}"""))
        assertEquals("sess_e2e_1", client().createSession("First conversation").getOrThrow())
        val create = request()
        assertEquals("/api/sessions", create.path)
        assertEquals("{\"title\":\"First conversation\"}", body(create))
        assertEquals("Bearer test-key", create.getHeader("Authorization"))

        val repo = ConversationRepository(ConversationStore(temporaryFolder.newFolder("store")))
        repo.startNew(); repo.bindSession(a, "sess_e2e_1", ChatTransportKind.SESSIONS)

        fun stream(message: String, body: String, deltaExpected: String, finalExpected: String?, route: String) {
            originA.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream").setBody(body))
            val done = CountDownLatch(1); val deltas = StringBuilder(); var final: String? = null; var runtime: RuntimeInfo? = null; var completions = 0
            val source = client().streamSessionTurn("sess_e2e_1", message, object : HermesClient.StreamCallbacks {
                override fun onDelta(textDelta: String) { deltas.append(textDelta) }
                override fun onFinalContent(text: String) { final = text }
                override fun onRuntime(info: RuntimeInfo) { runtime = info }
                override fun onComplete() { completions++; done.countDown() }
                override fun onError(message: String) { done.countDown() }
            })
            assertTrue(done.await(5, TimeUnit.SECONDS)); source.cancel()
            assertEquals(deltaExpected, deltas.toString()); finalExpected?.let { assertEquals(it, final) }; assertEquals(1, completions)
            assertEquals(route, runtime?.route_source)
            val req = request(); assertEquals("{\"message\":\"$message\"}", body(req))
            assertEquals(setOf("message"), Json.parseToJsonElement(body(req)).jsonObject.keys)
            assertEquals("text/event-stream", req.getHeader("Accept")); assertTrue(req.path!!.endsWith("/chat/stream"))
            assertFalse(req.headers.names().contains("X-Hermes-Session-Id"))
        }

        stream("first message", sse("run.started" to "{}", "assistant.delta" to "{\"delta\":\"Hel\"}", "assistant.delta" to "{\"delta\":\"lo\"}", "assistant.completed" to "{\"content\":\"Hello there\"}", "run.completed" to "{\"runtime\":{\"model\":\"\",\"route_source\":\"global\"}}", "done" to "{}"), "Hello", "Hello there", "global")
        repo.addMessage("user", "first message"); repo.addMessage("assistant", "Hello there")

        originA.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"hermes.session.model_lock","session_id":"sess_e2e_1","automatic":false,"runtime":{"provider":"","model":"m1","route_source":"session_model_lock","model_lock":"accepted"}}"""))
        val locked = client().setSessionModel("sess_e2e_1", "m1").getOrThrow(); val lockRequest = request()
        assertEquals("{\"model\":\"m1\"}", body(lockRequest)); assertEquals("m1", locked.runtime?.model); assertEquals("session_model_lock", locked.runtime?.route_source)
        val selection = ModelSelection(); selection.onSelectorAvailability(true, true, true); selection.onSetAck("m1", locked.runtime)
        assertEquals("m1", selection.state.label); assertTrue(selection.state.locked); assertEquals("session_model_lock", selection.effective?.routeSource)
        stream("second message", sse("assistant.delta" to "{\"delta\":\"Locked\"}", "assistant.delta" to "{\"delta\":\" reply\"}", "assistant.completed" to "{\"content\":\"Locked reply\"}", "run.completed" to "{\"runtime\":{\"model\":\"m1\",\"route_source\":\"session_model_lock\"}}", "done" to "{}"), "Locked reply", "Locked reply", "session_model_lock")
        repo.addMessage("user", "second message"); repo.addMessage("assistant", "Locked reply")

        originA.enqueue(MockResponse().setResponseCode(200).setBody("""{"object":"hermes.session.model_lock","session_id":"sess_e2e_1","automatic":true,"runtime":{"provider":"","model":"","route_source":"global","model_lock":"cleared"}}"""))
        val cleared = client().clearSessionModel("sess_e2e_1").getOrThrow(); assertEquals("{\"model\":null}", body(request())); assertTrue(cleared.automatic); assertEquals("global", cleared.runtime?.route_source)
        selection.onClearAck(cleared.runtime); assertEquals("Automatic", selection.state.label); assertFalse(selection.state.locked)
        stream("third message", sse("run.completed" to "{\"runtime\":{\"model\":\"\",\"route_source\":\"global\"}}", "done" to "{}"), "", null, "global")
        repo.addMessage("user", "third message"); repo.addMessage("assistant", "")
        repo.markUsed(); repo.persist()
        val id = repo.list().single().id
        val repo2 = ConversationRepository(ConversationStore(temporaryFolder.root.resolve("store"))); repo2.open(id)
        assertEquals("sess_e2e_1", repo2.sessionId); assertEquals(a, repo2.origin); assertEquals(ChatTransportKind.SESSIONS, repo2.transport); assertNotNull(repo2.lastUsedAt)

        originA.enqueue(MockResponse().setResponseCode(200).setBody("""{"session_id":"sess_e2e_1","data":[{"role":"user","content":"first message","timestamp":1},{"role":"assistant","content":"Hello there","timestamp":2},{"role":"user","content":"second message","timestamp":3},{"role":"assistant","content":"Locked reply","timestamp":4}]}"""))
        val history = client().getSessionMessages("sess_e2e_1").getOrThrow(); val historyRequest = request()
        assertEquals("/api/sessions/sess_e2e_1/messages?order=oldest&limit=500", historyRequest.path)
        repo2.replaceAllMessages(history.data.map { UiMessage(it.role, it.content) }); assertEquals(4, repo2.messages.size)
        recorded.forEach { r ->
            assertFalse(r.path!!.contains("/v1/chat/completions"))
            assertEquals("Bearer test-key", r.getHeader("Authorization"))
            if (r.path!!.endsWith("/chat/stream")) {
                assertEquals(setOf("message"), Json.parseToJsonElement(body(r)).jsonObject.keys)
            }
        }
        val b = base(originB); assertTrue(ChatTransportSelector.decide(OriginCapabilities(b, CapabilityState.SUPPORTED, features), ChatTransportKind.SESSIONS, a, b, true) is ChatTransportDecision.Unavailable); assertEquals(0, originB.requestCount)
    }

    @Test
    fun `sessions 404 session missing never falls back to legacy`() = runBlocking {
        originA.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":{"code":"session_not_found","message":"gone"}}"""))
        val before = originA.requestCount; val result = HermesClient(base(originA), "test-key").sendSessionTurn("sess_missing", "message")
        val error = result.exceptionOrNull() as? HermesHttpError
        assertTrue(result.isFailure); assertNotNull(error); assertTrue(error!!.isSessionMissing); assertEquals(before + 1, originA.requestCount)
        assertFalse(originA.takeRequest()!!.path!!.contains("/v1/chat/completions"))
    }

    @Test
    fun `first turns retain one bubble and retry duplicate titles`() = runBlocking {
        val titles = mutableSetOf<String>()
        var nextId = 0
        originA.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path == "/api/sessions" -> {
                        val body = request.body.clone().readUtf8()
                        val title = body.substringAfter("\"title\":\"").substringBefore("\"}")
                        if (!titles.add(title)) MockResponse().setResponseCode(400)
                            .setBody("{\"error\":{\"code\":\"session_exists\"}}")
                        else MockResponse().setResponseCode(201).setBody("{\"session\":{\"id\":\"flow-${++nextId}\"}}")
                    }
                    request.path!!.contains("/chat/stream") -> MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "text/event-stream")
                        .setBody(sse("assistant.completed" to "{\"content\":\"reply\"}", "done" to "{}"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val repo = ConversationRepository(ConversationStore(temporaryFolder.newFolder("first-turn")))
        val client = HermesClient(base(originA), "test-key")
        suspend fun turn() {
            val text = repo.queueFirstTurn("same text")
            val sid = createSessionForFirstTurn(client, sessionTitleFrom(text), repo.activeConversationId).getOrThrow()
            repo.bindSession(base(originA), sid, ChatTransportKind.SESSIONS)
            val done = CountDownLatch(1)
            client.streamSessionTurn(sid, text, object : HermesClient.StreamCallbacks {
                override fun onDelta(textDelta: String) = Unit
                override fun onFinalContent(text: String) = Unit
                override fun onToolProgress(tool: String, label: String?, running: Boolean) = Unit
                override fun onComplete() { done.countDown() }
                override fun onError(message: String) { done.countDown() }
            })
            assertTrue(done.await(5, TimeUnit.SECONDS))
            repo.addMessage("assistant", "reply")
        }
        turn(); val first = repo.sessionId; assertEquals(1, repo.messages.count { it.role == "user" })
        repo.startNew(); turn(); val second = repo.sessionId
        assertNotEquals(first, second); assertEquals(1, repo.messages.count { it.role == "user" })
        assertEquals(5, originA.requestCount)
        assertEquals(2, (0 until 5).map { originA.takeRequest()!!.path }.count { it!!.contains("/chat/stream") })
    }

    @Test
    fun `partial stream is not persisted as successful completion`() = runBlocking {
        val dir = temporaryFolder.newFolder("partial-persistence")
        val store = ConversationStore(dir)
        val repo = ConversationRepository(store)
        repo.startNew()
        repo.bindSession(base(originA), "partial-session", ChatTransportKind.SESSIONS)
        repo.addMessage("user", "hello")
        val partial = "assistant.delta:partial text"
        originA.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody(sse("assistant.delta" to "{\"delta\":\"partial \"}", "assistant.delta" to "{\"delta\":\"text\"}"))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val error = CountDownLatch(1)
        val accumulated = StringBuilder()
        var errorCalled = false
        var completeCount = 0
        client().streamSessionTurn("partial-session", "hello", object : HermesClient.StreamCallbacks {
            override fun onDelta(textDelta: String) { accumulated.append(textDelta) }
            override fun onComplete() { completeCount++; repo.addMessage("assistant", accumulated.toString()); error.countDown() }
            override fun onError(streamError: Throwable) { errorCalled = true; error.countDown() }
        })
        assertTrue(error.await(5, TimeUnit.SECONDS))
        assertTrue(errorCalled)
        assertEquals(0, completeCount)
        assertTrue(repo.messages.none { it.role == "assistant" })
        repo.persist()
        assertFalse(dir.resolve("${repo.activeConversationId}.json").readText().contains(partial.substringAfter(':')))

        val positiveRepo = ConversationRepository(ConversationStore(temporaryFolder.newFolder("complete-persistence")))
        positiveRepo.startNew()
        positiveRepo.bindSession(base(originA), "complete-session", ChatTransportKind.SESSIONS)
        originA.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/event-stream")
                .setBody(sse("assistant.delta" to "{\"delta\":\"complete text\"}", "done" to "{}"))
        )
        val complete = CountDownLatch(1)
        val positiveAccumulated = StringBuilder()
        var positiveCompleteCount = 0
        client().streamSessionTurn("complete-session", "hello", object : HermesClient.StreamCallbacks {
            override fun onDelta(textDelta: String) { positiveAccumulated.append(textDelta) }
            override fun onComplete() {
                positiveCompleteCount++
                positiveRepo.addMessage("assistant", positiveAccumulated.toString())
                complete.countDown()
            }
            override fun onError(streamError: Throwable) { complete.countDown() }
        })
        assertTrue(complete.await(5, TimeUnit.SECONDS))
        assertEquals(1, positiveCompleteCount)
        assertTrue(positiveRepo.messages.any { it.role == "assistant" && it.text == "complete text" })
    }
}
