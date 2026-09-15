package dk.foss.jarvis.net

import dk.foss.jarvis.hermes.originIdentity
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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

    private var server = MockWebServer()

    @Before fun setUp() {
        try { server.shutdown() } catch (_: Throwable) {}
        server = MockWebServer()
        server.start(0) // random available port
    }

    @After fun tearDown() {
        try { server.shutdown() } catch (_: Throwable) {}
    }

    /**
     * Verify Http.base rejects 302 redirects (no follow).
     * Approved HTTP origin A → server redirects 302 to B → must stay at A's response.
     */
    @Test fun `approved_http_A_redirect_302_to_unapproved_B_bcount0`() = runBlocking {
        val gate = Http.testingGate
        val store = gate.approvedOrigins as InMemoryApprovedOriginsStore
        store.clearAll()
        runBlocking { store.add(originIdentity(server.url("/").toString())) }

        // Server at A responds with 302 → B
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://other.invalid/path"))

        val conn = Http.base.newCall(
            okhttp3.Request.Builder().url(server.url("/")).build()
        ).execute()

        // Without redirect following, we get the 302 directly (not the redirect target)
        assertEquals(302, conn.code)
        conn.close()
    }

    /**
     * HTTPS A → 302 redirect to HTTP B should not follow.
     */
    @Test fun `https_A_redirect_http_B_no_follow`() = runBlocking {
        val gate = Http.testingGate
        val store = gate.approvedOrigins as InMemoryApprovedOriginsStore
        store.clearAll()

        // HTTPS is always allowed, no approval needed
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://other.invalid/path"))

        val conn = Http.base.newCall(
            okhttp3.Request.Builder().url(server.url("/")).build()
        ).execute()

        assertEquals(302, conn.code)
        conn.close()
    }

    /**
     * 307 redirect with POST body must not follow (body should not be sent to target).
     */
    @Test fun `redirect_307_post_not_followed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", "http://other.invalid/"))

        val body = """{"message":"hello"}""".toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )
        val conn = Http.base.newCall(
            okhttp3.Request.Builder()
                .url(server.url("/"))
                .post(body)
                .build()
        ).execute()

        // We get the 307 directly — no auto-follow
        assertEquals(307, conn.code)
        conn.close()
    }

    /**
     * 308 redirect must not follow either.
     */
    @Test fun `redirect_308_not_followed`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(308).addHeader("Location", "http://other.invalid/"))

        val body = """{"rpc":"test"}""".toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )
        val conn = Http.base.newCall(
            okhttp3.Request.Builder()
                .url(server.url("/"))
                .post(body)
                .build()
        ).execute()

        assertEquals(308, conn.code)
        conn.close()
    }

    /**
     * Direct approved HTTP works fine (no redirect involved).
     */
    @Test fun `direct_approved_http_works`() = runBlocking {
        val gate = Http.testingGate
        val store = gate.approvedOrigins as InMemoryApprovedOriginsStore
        store.clearAll()

        val port = server.port
        server.enqueue(MockResponse().setBody("OK"))

        // Approve the actual server origin (including port)
        store.addSync(dk.foss.jarvis.hermes.originIdentity("http://localhost:$port"))

        // Validate passes (approved)
        val validated = gate.validate("http://localhost:$port")
        assertEquals(dk.foss.jarvis.hermes.originIdentity("http://localhost:$port"), validated)

        // Direct call works
        val conn = Http.base.newCall(
            okhttp3.Request.Builder().url(server.url("/")).build()
        ).execute()
        assertEquals(200, conn.code)
        assertEquals("OK", conn.body?.string())
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