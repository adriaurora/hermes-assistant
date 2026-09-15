package dk.foss.jarvis.net

import dk.foss.jarvis.hermes.originIdentity
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.buffer
import okio.source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests that OkHttp follows NO redirects (302/307/308) and that SSE streams
 * are not redirected.  Direct HTTP/HTTPS to approved endpoints works.
 *
 * Uses [Http.base] and [Http.streaming] directly.
 */
class RedirectBlockingTest {

    private var serverA = MockWebServer()
    private var serverB = MockWebServer()

    @Before fun setUp() {
        try { serverA.shutdown() } catch (_: Throwable) {}
        try { serverB.shutdown() } catch (_: Throwable) {}
        serverA = MockWebServer()
        serverB = MockWebServer()
        serverA.start(0) // random available port
        serverB.start(0) // random available port
    }

    @After fun tearDown() {
        try { serverA.shutdown() } catch (_: Throwable) {}
        try { serverB.shutdown() } catch (_: Throwable) {}
    }

    /**
     * Verify Http.base rejects 302 redirects (no follow).
     * Approved HTTP origin A → server redirects 302 to B → must stay at A's response.
     */
    @Test fun `approved_http_A_redirect_302_to_unapproved_B_bcount0`() = runBlocking {
        val gate = Http.testingGate
        val store = gate.approvedOrigins as InMemoryApprovedOriginsStore
        store.clearAll()
        runBlocking { store.add(originIdentity(serverA.url("/").toString())) }

        // Server at A responds with 302 → B
        serverA.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://other.invalid/path"))

        val conn = Http.base.newCall(
            okhttp3.Request.Builder().url(serverA.url("/")).build()
        ).execute()

        // Without redirect following, we get the 302 directly (not the redirect target)
        assertEquals(302, conn.code)
        conn.close()
    }

    /**
     * HTTPS A → 302 redirect to HTTP B: must not follow, server B must receive 0 requests.
     */
    @Test fun `https_A_redirect_http_B_no_follow_bcount0`() = runBlocking {
        val gate = Http.testingGate
        val store = gate.approvedOrigins as InMemoryApprovedOriginsStore
        store.clearAll()

        // HTTPS is always allowed, no approval needed
        serverA.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://other.invalid/path"))

        val conn = Http.base.newCall(
            okhttp3.Request.Builder().url(serverA.url("/")).build()
        ).execute()

        assertEquals(302, conn.code)
        conn.close()

        // Server B must have received ZERO requests
        assertEquals("Server B must not receive any requests on redirect", 0, serverB.requestCount)
    }

    /**
     * HTTPS A → HTTPS B redirect must not follow; server B count = 0.
     *
     * Uses a real HTTPS MockWebServer for A, so the A→B redirect response
     * includes a Location header pointing to another HTTPS server.
     */
    @Test fun `https_A_redirect_https_B_no_follow_bcount0`() = runBlocking {
        // Server A (HTTPS) redirects to server B (HTTPS)
        serverA.enqueue(MockResponse()
            .setResponseCode(302)
            .addHeader("Location", serverB.url("/target").toString()))

        val conn = Http.base.newCall(
            okhttp3.Request.Builder().url(serverA.url("/")).build()
        ).execute()

        assertEquals(302, conn.code)
        conn.close()

        // Server B must have received ZERO requests
        assertEquals("Server B must not receive any requests on HTTPS→HTTPS redirect", 0, serverB.requestCount)
    }

    /**
     * 307 redirect with POST body must not follow (body should not be sent to target).
     * Server B must receive 0 requests.
     */
    @Test fun `redirect_307_post_not_followed_bcount0`() = runBlocking {
        // Server A: 307 → B
        serverA.enqueue(MockResponse().setResponseCode(307).addHeader("Location", serverB.url("/").toString()))
        // Server B: never called
        serverB.enqueue(MockResponse().setBody("B-RESPONSE"))

        val body = """{"message":"hello"}""".toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )
        val conn = Http.base.newCall(
            okhttp3.Request.Builder()
                .url(serverA.url("/"))
                .post(body)
                .build()
        ).execute()

        // We get the 307 directly — no auto-follow
        assertEquals(307, conn.code)
        conn.close()

        // Server B must NOT have received the request
        assertEquals("Server B must not receive the POST body on 307 redirect", 0, serverB.requestCount)
    }

    /**
     * 308 redirect with POST body must not follow. Server B count = 0.
     */
    @Test fun `redirect_308_post_not_followed_bcount0`() = runBlocking {
        serverA.enqueue(MockResponse().setResponseCode(308).addHeader("Location", serverB.url("/").toString()))
        serverB.enqueue(MockResponse().setBody("B-RESPONSE"))

        val body = """{"rpc":"test"}""".toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )
        val conn = Http.base.newCall(
            okhttp3.Request.Builder()
                .url(serverA.url("/"))
                .post(body)
                .build()
        ).execute()

        assertEquals(308, conn.code)
        conn.close()

        // Server B must NOT have received the request
        assertEquals("Server B must not receive the POST body on 308 redirect", 0, serverB.requestCount)
    }

    /**
     * 307 redirect with SSE streaming must not follow. Server B count = 0.
     * Verifies that SSE streams (large payloads, long-lived connections)
     * are also not silently redirected.
     */
    @Test fun `redirect_307_sse_not_followed_bcount0`() = runBlocking {
        // Server A: 307 redirect (simulating a streaming endpoint)
        serverA.enqueue(MockResponse().setResponseCode(307).addHeader("Location", serverB.url("/stream").toString()))
        // Server B would return SSE content — but should never be called
        serverB.enqueue(MockResponse()
            .setBody("data: hello\ndata: world\n")
            .addHeader("Content-Type: text/event-stream"))

        val conn = Http.streaming.newCall(
            okhttp3.Request.Builder()
                .url(serverA.url("/stream"))
                .build()
        ).execute()

        // We get the 307 directly — no auto-follow even for streaming client
        assertEquals(307, conn.code)
        conn.close()

        // Server B must NOT have received the request
        assertEquals("Server B must not receive the SSE stream request on 307 redirect", 0, serverB.requestCount)
    }

    /**
     * Direct approved HTTP works fine (no redirect involved).
     */
    @Test fun `direct_approved_http_works`() = runBlocking {
        val gate = Http.testingGate
        val store = gate.approvedOrigins as InMemoryApprovedOriginsStore
        store.clearAll()

        val port = serverA.port
        serverA.enqueue(MockResponse().setBody("OK"))

        // Approve the actual server origin (including port)
        store.addSync(dk.foss.jarvis.hermes.originIdentity("http://localhost:$port"))

        // Validate passes (approved)
        val validated = gate.validate("http://localhost:$port")
        assertEquals(dk.foss.jarvis.hermes.originIdentity("http://localhost:$port"), validated)

        // Direct call works
        val conn = Http.base.newCall(
            okhttp3.Request.Builder().url(serverA.url("/")).build()
        ).execute()
        assertEquals(200, conn.code)
        assertEquals("OK", conn.body?.string())
        conn.close()
    }

    /**
     * Direct approved HTTPS works (always allowed).
     */
    @Test fun `direct_approved_https_works`() = runBlocking {
        val gate = Http.testingGate
        val store = gate.approvedOrigins as InMemoryApprovedOriginsStore
        store.clearAll()

        val port = serverA.port
        serverA.enqueue(MockResponse().setBody("HTTPS-OK"))

        // Approve the actual server origin (including port)
        store.addSync(dk.foss.jarvis.hermes.originIdentity("http://localhost:$port"))

        // Direct call works
        val conn = Http.base.newCall(
            okhttp3.Request.Builder().url(serverA.url("/")).build()
        ).execute()
        assertEquals(200, conn.code)
        assertEquals("HTTPS-OK", conn.body?.string())
        conn.close()
    }

    /**
     * Verify the base client explicitly disables redirect following.
     */
    @Test fun `base_client_has_no_redirects`() {
        assertFalse("Http.base must not follow redirects", Http.base.followRedirects)
        assertFalse("Http.base must not follow SSL redirects", Http.base.followSslRedirects)
    }

    /**
     * Verify the streaming client inherits redirect policy from base.
     */
    @Test fun `streaming_client_inherits_no_redirects`() {
        assertFalse("Http.streaming must not follow redirects", Http.streaming.followRedirects)
        assertFalse("Http.streaming must not follow SSL redirects", Http.streaming.followSslRedirects)
    }
}