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
 * Tests CapabilityRegistry caching and error classification via MockWebServer.
 */
class CapabilityRegistryTest {

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

    private fun requestCount(): Int = server.requestCount

    // 1. Success 200 with envelope → SUPPORTED + features parsed; second call cached
    @Test
    fun `success 200 envelope yields SUPPORTED and caches`() = runBlocking {
        val json = """{"features":{"session_chat":true,"session_chat_streaming":true}}"""
        server.enqueue(MockResponse().setResponseCode(200).setBody(json))

        val result1 = CapabilityRegistry.capabilities("https://h1:8642") { client().getCapabilities() }
        assertEquals("https://h1:8642", result1.origin)
        assertEquals(CapabilityState.SUPPORTED, result1.state)
        assertTrue(result1.features.session_chat)
        assertTrue(result1.features.session_chat_streaming)
        assertEquals(1, requestCount())

        // Second call should be cached — no new request
        val result2 = CapabilityRegistry.capabilities("https://h1:8642") { client().getCapabilities() }
        assertEquals(CapabilityState.SUPPORTED, result2.state)
        assertEquals(1, requestCount())
    }

    // 2. 404 → UNSUPPORTED; cached (second call no new request); features all false
    @Test
    fun `404 yields UNSUPPORTED and caches`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))

        val result1 = CapabilityRegistry.capabilities("https://h2:8642") { client().getCapabilities() }
        assertEquals(CapabilityState.UNSUPPORTED, result1.state)
        assertTrue(result1.features.session_chat.not())
        assertEquals(1, requestCount())

        // Second call cached
        val result2 = CapabilityRegistry.capabilities("https://h2:8642") { client().getCapabilities() }
        assertEquals(CapabilityState.UNSUPPORTED, result2.state)
        assertEquals(1, requestCount())
    }

    // 3. 401 → UNKNOWN; NOT cached (second call hits server again)
    @Test
    fun `401 yields UNKNOWN not cached`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(401))

        val result1 = CapabilityRegistry.capabilities("https://h3:8642") { client().getCapabilities() }
        assertEquals(CapabilityState.UNKNOWN, result1.state)
        assertEquals(1, requestCount())

        // Second call should hit server again (not cached)
        val result2 = CapabilityRegistry.capabilities("https://h3:8642") { client().getCapabilities() }
        assertEquals(CapabilityState.UNKNOWN, result2.state)
        assertEquals(2, requestCount())
    }

    // 4. 403 → UNKNOWN
    @Test
    fun `403 yields UNKNOWN`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403))
        val result = CapabilityRegistry.capabilities("https://h4:8642") { client().getCapabilities() }
        assertEquals(CapabilityState.UNKNOWN, result.state)
    }

    // 5. 500 → UNKNOWN
    @Test
    fun `500 yields UNKNOWN`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = CapabilityRegistry.capabilities("https://h5:8642") { client().getCapabilities() }
        assertEquals(CapabilityState.UNKNOWN, result.state)
    }

    // 6. Network failure (DISCONNECT_AT_START) → UNKNOWN
    @Test
    fun `network failure yields UNKNOWN`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        val result = CapabilityRegistry.capabilities("https://h6:8642") { client().getCapabilities() }
        assertEquals(CapabilityState.UNKNOWN, result.state)
    }

    // 7. UNKNOWN must never be reported as SUPPORTED or UNSUPPORTED
    @Test
    fun `UNKNOWN never reported as SUPPORTED or UNSUPPORTED`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = CapabilityRegistry.capabilities("https://h7:8642") { client().getCapabilities() }
        assertEquals(CapabilityState.UNKNOWN, result.state)
        assertFalse(result.state == CapabilityState.SUPPORTED)
        assertFalse(result.state == CapabilityState.UNSUPPORTED)
    }

    // 8. Different origins do not share cache
    @Test
    fun `different origins independent cache`() = runBlocking {
        val server1 = MockWebServer()
        val server2 = MockWebServer()
        server1.start()
        server2.start()

        server1.enqueue(MockResponse().setResponseCode(200).setBody("""{"features":{"session_chat":true}}"""))
        server2.enqueue(MockResponse().setResponseCode(404))

        val c1 = HermesClient(server1.url("/").toString().trimEnd('/'), "k")
        val c2 = HermesClient(server2.url("/").toString().trimEnd('/'), "k")

        val r1 = CapabilityRegistry.capabilities("https://s1:8642") { c1.getCapabilities() }
        assertEquals(CapabilityState.SUPPORTED, r1.state)

        val r2 = CapabilityRegistry.capabilities("https://s2:8642") { c2.getCapabilities() }
        assertEquals(CapabilityState.UNSUPPORTED, r2.state)

        server1.shutdown()
        server2.shutdown()
    }

    // 9. evict clears cache
    @Test
    fun `evict clears cache`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"features":{"session_chat":true}}"""))
        val result1 = CapabilityRegistry.capabilities("https://h8:8642") { client().getCapabilities() }
        assertEquals(CapabilityState.SUPPORTED, result1.state)
        assertEquals(1, requestCount())

        CapabilityRegistry.evict("https://h8:8642")
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"features":{"session_chat":true}}"""))

        val result2 = CapabilityRegistry.capabilities("https://h8:8642") { client().getCapabilities() }
        assertEquals(CapabilityState.SUPPORTED, result2.state)
        assertEquals(2, requestCount())
    }
}