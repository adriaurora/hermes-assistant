package dk.foss.jarvis.hermes

import okio.Buffer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Tests SSE parsing via streamSessionTurn with real SSE events from MockWebServer.
 * Uses CountDownLatch to wait for async SSE callbacks.
 */
class SessionSseParserTest {

    private val server = MockWebServer()
    private val mediaType = "text/event-stream; charset=utf-8".toMediaType()

    @Before fun setUp() { server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun client(): HermesClient =
        HermesClient(server.url("/").toString().trimEnd('/'), "test-key")

    /** Build SSE text: each line is "event: <type>\ndata: <json>\n\n" */
    private fun buildSse(type: String, json: String): String = "event: $type\ndata: $json\n\n"

    /** Build SSE delta event with text extracted from delta field */
    private fun buildDeltaSse(text: String): String = buildSse("assistant.delta", """{"delta":"$text"}""")

    /** Build SSE completed event with content field */
    private fun buildCompletedSse(content: String): String = buildSse("assistant.completed", """{"content":"$content"}""")

    private fun await(cb: TestSseCallbacks, timeoutMs: Long = 5000): Boolean = cb.await(timeoutMs)

    // 1. Fragmented assistant.delta across chunk boundaries accumulates exact full text
    @Test
    fun `fragmented delta accumulates full text`() = runBlocking {
        val sse = buildSse("assistant.delta", """{"delta":"Hel"}""") +
                  buildSse("assistant.delta", """{"delta":"lo"}""") +
                  buildCompletedSse("Hello") +
                  buildSse("done", "")

        val buffer = Buffer().writeUtf8(sse)
        // Use small chunk size to force fragmentation
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setChunkedBody(buffer, 15))

        val cb = TestSseCallbacks()
        val es = client().streamSessionTurn("s1", "hi", cb)
        await(cb)
        es.cancel()

        assertEquals("Hello", cb.deltaAccum.toString())
        assertEquals("Hello", cb.finalContent)
    }

    // 2. Multiple events in one chunk (whole body delivered at once)
    @Test
    fun `multiple events in one chunk parsed in order`() = runBlocking {
        val sse = buildSse("assistant.delta", """{"delta":"a"}""") +
                  buildSse("assistant.delta", """{"delta":"b"}""") +
                  buildCompletedSse("ab") +
                  buildSse("done", "")
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sse))

        val cb = TestSseCallbacks()
        val es = client().streamSessionTurn("s1", "hi", cb)
        await(cb)
        es.cancel()

        assertEquals("ab", cb.deltaAccum.toString())
        assertEquals("ab", cb.finalContent)
    }

    // 3. : keepalive comment lines ignored
    @Test
    fun `keepalive comment lines ignored`() = runBlocking {
        val sse = ": keepalive\n\nevent: assistant.delta\ndata: {\"delta\":\"hi\"}\n\n: keepalive\n\nevent: done\ndata: \n\n"
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sse))

        val cb = TestSseCallbacks()
        val es = client().streamSessionTurn("s1", "hi", cb)
        await(cb)
        es.cancel()

        assertEquals(1, cb.deltaCalls)
        assertEquals("hi", cb.deltaAccum.toString())
        assertTrue(cb.completed)
    }

    // 4. assistant.completed with {"content":"final answer"} → onFinalContent + not re-emitted via onDelta
    @Test
    fun `assistant_completed_onFinalContent_not_re_emitted_via_delta`() = runBlocking {
        val sse = buildSse("assistant.delta", """{"delta":"part 1"}""") +
                  buildCompletedSse("final answer") +
                  buildSse("done", "")
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sse))

        val cb = TestSseCallbacks()
        val es = client().streamSessionTurn("s1", "hi", cb)
        await(cb)
        es.cancel()

        assertEquals("part 1", cb.deltaAccum.toString())
        assertEquals("final answer", cb.finalContent)
        assertFalse(cb.onErrorCalled)
    }

    // 5. tool.started → onToolProgress running=true; tool.completed → running=false
    @Test
    fun `tool_started_running_true`() = runBlocking {
        val sse = buildSse("tool.started", """{"tool":"web_search","label":"Searching","status":"running"}""") +
                  buildSse("tool.completed", """{"tool":"web_search","label":"Done","status":"completed"}""") +
                  buildSse("done", "")
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sse))

        val cb = TestSseCallbacks()
        val es = client().streamSessionTurn("s1", "hi", cb)
        await(cb)
        es.cancel()

        assertEquals(2, cb.toolProgressCalls.size)
        assertTrue(cb.toolProgressCalls[0].running)
        assertEquals("web_search", cb.toolProgressCalls[0].tool)
        assertEquals("Searching", cb.toolProgressCalls[0].label)
        assertFalse(cb.toolProgressCalls[1].running)
        assertEquals("web_search", cb.toolProgressCalls[1].tool)
    }

    // 6. tool.failed → running=false
    @Test
    fun `tool_failed_running_false`() = runBlocking {
        val sse = buildSse("tool.failed", """{"tool":"execute","status":"failed"}""") +
                  buildSse("done", "")
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sse))

        val cb = TestSseCallbacks()
        val es = client().streamSessionTurn("s1", "hi", cb)
        await(cb)
        es.cancel()

        assertTrue(cb.toolProgressCalls.isNotEmpty())
        assertFalse(cb.toolProgressCalls[0].running)
    }

    // 7. run.completed with runtime captured
    @Test
    fun `run_completed_captures_runtime_and_onComplete`() = runBlocking {
        val sse = buildSse("assistant.delta", """{"delta":"answer"}""") +
                  buildSse("run.completed", """{"runtime":{"model":"m1","route_source":"session_model_lock","model_lock":"accepted"}}""") +
                  buildSse("done", "")
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sse))

        val cb = TestSseCallbacks()
        val es = client().streamSessionTurn("s1", "hi", cb)
        await(cb)
        es.cancel()

        assertEquals("m1", cb.runtime?.model)
        assertEquals("session_model_lock", cb.runtime?.route_source)
        assertEquals(1, cb.onCompleteCount)
    }

    // 8. done event → onComplete exactly once
    @Test
    fun `done_event_onComplete_exactly_once`() = runBlocking {
        val sse = buildSse("assistant.delta", """{"delta":"x"}""") +
                  buildSse("done", "")
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sse))

        val cb = TestSseCallbacks()
        val es = client().streamSessionTurn("s1", "hi", cb)
        await(cb)
        es.cancel()

        assertEquals(1, cb.onCompleteCount)
    }

    // 9. event error data {"message":"boom"} → onError once, no onComplete after
    @Test
    fun `error_event_onError_once_no_onComplete`() = runBlocking {
        val sse = buildSse("error", """{"message":"boom"}""")
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sse))

        val cb = TestSseCallbacks()
        val es = client().streamSessionTurn("s1", "hi", cb)
        await(cb)
        es.cancel()

        assertEquals("boom", cb.error)
        assertTrue(cb.onErrorCalled)
        assertEquals(0, cb.onCompleteCount)
    }

    // 10. Mid-stream disconnect: exactly ONE terminal callback (error or complete)
    @Test
    fun `mid_stream_disconnect_exactly_one_terminal_callback`() = runBlocking {
        val sse = buildSse("assistant.delta", """{"delta":"p"}""")
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            .setBody(sse))

        val cb = TestSseCallbacks()
        val es = client().streamSessionTurn("s1", "hi", cb)
        await(cb)
        es.cancel()

        // Exactly one terminal callback: error OR complete, never both
        val terminalCount = cb.onCompleteCount + (if (cb.onErrorCalled) 1 else 0)
        assertEquals(1, terminalCount)
    }

    // 10b. Mid-stream disconnect fires onError, NOT onComplete
    @Test
    fun `mid_stream_disconnect_onError_not_onComplete`() = runBlocking {
        val sse = buildSse("assistant.delta", """{"delta":"partial"}""")
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            .setBody(sse))

        val cb = TestSseCallbacks()
        val es = client().streamSessionTurn("s1", "hi", cb)
        await(cb)
        es.cancel()

        // Must fire onError (stream closed before terminal), NOT onComplete
        assertTrue("Mid-stream disconnect must fire onError", cb.onErrorCalled)
        assertTrue("Error must be non-null", cb.error != null)
        assertEquals(0, cb.onCompleteCount)
    }

    // 11. run.started / message.started / unknown event types → no callbacks
    @Test
    fun `run_started_no_callbacks`() = runBlocking {
        val sse = buildSse("run.started", """{}""") +
                  buildSse("message.started", """{}""") +
                  buildSse("unknown.event", """{}""") +
                  buildSse("done", "")
        server.enqueue(MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sse))

        val cb = TestSseCallbacks()
        val es = client().streamSessionTurn("s1", "hi", cb)
        await(cb)
        es.cancel()

        // No callbacks except onComplete from done
        assertEquals(0, cb.deltaCalls)
        assertEquals(0, cb.toolProgressCalls.size)
        assertEquals(null, cb.runtime)
        assertEquals(1, cb.onCompleteCount)
    }
}

/** Helper class to collect SSE callbacks for testing. */
class TestSseCallbacks : HermesClient.StreamCallbacks {
    val deltaAccum = StringBuilder()
    var deltaCalls = 0
    var finalContent: String? = null
    var onErrorCalled = false
    var error: String? = null
    var completed = false
    var onCompleteCount = 0
    var runtime: RuntimeInfo? = null
    val toolProgressCalls = mutableListOf<ToolProgressEvent>()
    private val latch = CountDownLatch(1)

    override fun onDelta(textDelta: String) {
        deltaAccum.append(textDelta)
        deltaCalls++
    }
    override fun onFinalContent(text: String) { finalContent = text }
    override fun onError(message: String) {
        error = message
        onErrorCalled = true
        latch.countDown()
    }
    override fun onComplete() {
        completed = true
        onCompleteCount++
        latch.countDown()
    }
    override fun onRuntime(info: RuntimeInfo) { runtime = info }
    override fun onToolProgress(tool: String, label: String?, running: Boolean) {
        toolProgressCalls += ToolProgressEvent(tool, label, running)
    }

    fun await(timeoutMs: Long = 5000): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
}

data class ToolProgressEvent(val tool: String, val label: String?, val running: Boolean)