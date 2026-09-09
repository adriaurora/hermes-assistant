package dk.foss.jarvis.hermes

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/**
 * Regression guards for the retained legacy chat path (streamChat).
 */
class LegacyChatRegressionTest {

    private val server = MockWebServer()

    @Before fun setUp() { server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun client(): HermesClient =
        HermesClient(server.url("/").toString().trimEnd('/'), "test-key")

    // 1. streamChat against 200 SSE OpenAI-chunk body
    @Test
    fun `streamChat legacy POST path body headers deltas`() = runBlocking {
        val sseBody = """data: {"choices":[{"delta":{"role":"assistant","content":"Hello"}}]}

data: {"choices":[{"delta":{"content":" world"}}]}

data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

data: [DONE]

"""
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sseBody))

        val messages = listOf(
            ChatMessage("user", "say hi"),
            ChatMessage("assistant", "ok"),
            ChatMessage("user", "hello"),
        )
        val latch = CountDownLatch(1)
        val cb = object : HermesClient.StreamCallbacks {
            var deltaAccum = ""
            override fun onDelta(textDelta: String) { deltaAccum += textDelta }
            var completed = false
            override fun onComplete() { if (completed.not()) { completed = true; latch.countDown() } }
            var error = ""
            override fun onError(message: String) { if (completed.not()) { error = message; latch.countDown() } }
        }

        val es = client().streamChat(messages, "legacy-s", cb)
        latch.await(5, TimeUnit.SECONDS)
        es.cancel()

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("legacy-s", req.getHeader("X-Hermes-Session-Id"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("say hi"))
        assertTrue(body.contains("ok"))
        assertTrue(body.contains("hello"))
        assertFalse(body.contains("\"model\""))
        // Deltas concatenated
        assertEquals("Hello world", cb.deltaAccum)
        // onComplete fired once
        assertTrue(cb.completed)
    }

    // 2. 401 response → onError contains "HTTP 401" and NO fallback request
    @Test
    fun `streamChat 401 onError no fallback request`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"error":"unauthorized"}"""))

        val messages = listOf(ChatMessage("user", "hi"))
        val latch = CountDownLatch(1)
        val cb = object : HermesClient.StreamCallbacks {
            var error = ""
            override fun onError(message: String) { error = message; latch.countDown() }
            var completed = false
            override fun onComplete() { if (completed.not()) { completed = true; latch.countDown() } }
            var deltaAccum = ""
            override fun onDelta(textDelta: String) { deltaAccum += textDelta }
        }

        val es = client().streamChat(messages, "legacy-s", cb)
        latch.await(5, TimeUnit.SECONDS)
        es.cancel()

        assertTrue(cb.error.contains("401"))
        // Only one request was issued (the original 401), no fallback
        assertEquals(1, server.requestCount)
    }

    // 3. streamChat with model parameter sends model
    @Test
    fun `streamChat with model sends model in body`() = runBlocking {
        val sseBody = """data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

data: [DONE]

"""
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sseBody))

        val messages = listOf(ChatMessage("user", "hi"))
        val latch = CountDownLatch(1)
        val cb = object : HermesClient.StreamCallbacks {
            var deltaAccum = ""
            override fun onDelta(textDelta: String) { deltaAccum += textDelta }
            var completed = false
            override fun onComplete() { if (completed.not()) { completed = true; latch.countDown() } }
        }

        client().streamChat(messages, null, cb, model = "hermes-agent")
        latch.await(5, TimeUnit.SECONDS)

        val req = server.takeRequest()
        val body = req.body.readUtf8()
        assertTrue(body.contains("hermes-agent"))
    }

    // 4. streamChat without sessionId omits X-Hermes-Session-Id header
    @Test
    fun `streamChat no sessionId omits X-Hermes-Session-Id`() = runBlocking {
        val sseBody = """data: {"choices":[{"delta":{"content":"ok"},"finish_reason":"stop"}]}

data: [DONE]

"""
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sseBody))

        val messages = listOf(ChatMessage("user", "hi"))
        val latch = CountDownLatch(1)
        val cb = object : HermesClient.StreamCallbacks {
            var deltaAccum = ""
            override fun onDelta(textDelta: String) { deltaAccum += textDelta }
            var completed = false
            override fun onComplete() { if (completed.not()) { completed = true; latch.countDown() } }
        }

        client().streamChat(messages, null, cb)
        latch.await(5, TimeUnit.SECONDS)

        val req = server.takeRequest()
        assertNull(req.getHeader("X-Hermes-Session-Id"))
    }

    // 5. streamChat network failure → onError
    @Test
    fun `streamChat network failure onError`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))

        val messages = listOf(ChatMessage("user", "hi"))
        val latch = CountDownLatch(1)
        val cb = object : HermesClient.StreamCallbacks {
            var error = ""
            override fun onError(message: String) { error = message; latch.countDown() }
            var completed = false
            override fun onComplete() { if (completed.not()) { completed = true; latch.countDown() } }
            var deltaAccum = ""
            override fun onDelta(textDelta: String) { deltaAccum += textDelta }
        }

        val es = client().streamChat(messages, null, cb)
        latch.await(5, TimeUnit.SECONDS)
        es.cancel()

        assertFalse(cb.error.isEmpty())
    }

    // 6. streamChat onComplete exactly once even with multiple events
    @Test
    fun `streamChat onComplete exactly once`() = runBlocking {
        val sseBody = """data: {"choices":[{"delta":{"content":"a"},"finish_reason":null}]}

data: {"choices":[{"delta":{"content":"b"},"finish_reason":null}]}

data: {"choices":[{"delta":{},"finish_reason":"stop"}]}

data: [DONE]

"""
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "text/event-stream")
            .setBody(sseBody))

        val messages = listOf(ChatMessage("user", "hi"))
        var onCompleteCount = 0
        val latch = CountDownLatch(1)
        val cb = object : HermesClient.StreamCallbacks {
            var deltaAccum = ""
            override fun onDelta(textDelta: String) { deltaAccum += textDelta }
            override fun onComplete() { onCompleteCount++; latch.countDown() }
        }

        val es = client().streamChat(messages, null, cb)
        latch.await(5, TimeUnit.SECONDS)
        es.cancel()

        assertEquals(1, onCompleteCount)
        assertEquals("ab", cb.deltaAccum)
    }
}