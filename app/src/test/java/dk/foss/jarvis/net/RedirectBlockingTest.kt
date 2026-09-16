package dk.foss.jarvis.net

import dk.foss.jarvis.hermes.canonicalEndpointIdentity
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HeldCertificate
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests that OkHttp follows NO redirects (302/307/308) with **actual TLS**
 * servers.  Uses OkHttp's [HeldCertificate] to generate self-signed certs for
 * test servers A and B, and a test-only OkHttpClient that trusts them.
 *
 * Http.base and Http.streaming are NOT modified — their redirect flags
 * (followRedirects=false, followSslRedirects=false) stay intact.
 *
 * All test clients are derived from Http.base.newBuilder() /
 * Http.streaming.newBuilder() to inherit production followRedirects(false)
 * and followSslRedirects(false) while adding test-cert trust.
 *
 * No TLS bypass — no trust-all managers or hostname verification overrides.
 */
class RedirectBlockingTest {

    /** HandshakeCertificates providing both server cert + client trust anchor. */
    private var serverHandshake: HandshakeCertificates? = null
    private var serverA = MockWebServer()
    private var serverB = MockWebServer()

    /** Test client derived from Http.base.newBuilder() with test-cert trust. */
    private var testClient: OkHttpClient? = null

    /** Test streaming client derived from Http.streaming.newBuilder() with test-cert trust. */
    private var testStreamingClient: OkHttpClient? = null

    @Before fun setUp() {
        try { serverA.shutdown() } catch (_: Throwable) {}
        try { serverB.shutdown() } catch (_: Throwable) {}

        // Generate a self-signed certificate for the TLS test servers
        val heldCert = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1")
            .rsa2048()
            .build()

        // Build HandshakeCertificates: server cert + trust ourselves (so client validates)
        serverHandshake = HandshakeCertificates.Builder()
            .heldCertificate(heldCert)
            .addTrustedCertificate(heldCert.certificate)
            .build()

        // Derive test clients from Http.base / Http.streaming to inherit
        // production followRedirects(false) / followSslRedirects(false)
        testClient = Http.base.newBuilder()
            .sslSocketFactory(serverHandshake!!.sslSocketFactory(), serverHandshake!!.trustManager)
            .build()

        testStreamingClient = Http.streaming.newBuilder()
            .sslSocketFactory(serverHandshake!!.sslSocketFactory(), serverHandshake!!.trustManager)
            .build()

        // Start TLS servers using the same HandshakeCertificates (provides cert + key)
        serverA = MockWebServer().apply {
            useHttps(serverHandshake!!.sslSocketFactory(), false)
            start(0)
        }
        serverB = MockWebServer().apply {
            useHttps(serverHandshake!!.sslSocketFactory(), false)
            start(0)
        }
    }

    @After fun tearDown() {
        try { serverA.shutdown() } catch (_: Throwable) {}
        try { serverB.shutdown() } catch (_: Throwable) {}
    }

    /**
     * HTTPS A → 302 redirect to actual plaintext HTTP B: must not follow,
     * server B must receive 0 requests.
     * Uses actual TLS MockWebServer A, actual plaintext MockWebServer B.
     */
    @Test fun `https_A_redirect_http_B_no_follow_bcount0`() = runBlocking {
        val gate = Http.testingGate
        val store = gate.approvedOrigins as InMemoryApprovedOriginsStore
        store.clearAll()

        // Start a plaintext HTTP server B for the redirect target
        val serverBPlaintext = MockWebServer().also { it.start(0) }

        try {
            // HTTPS A redirects to actual plaintext HTTP B
            serverA.enqueue(MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "http://localhost:${serverBPlaintext.port}/path"))

            val conn = testClient!!.newCall(
                okhttp3.Request.Builder().url(serverA.url("/")).build()
            ).execute()

            assertEquals(302, conn.code)
            conn.close()

            // Server B must have received ZERO requests
            assertEquals("Server B must not receive any requests on redirect", 0, serverBPlaintext.requestCount)
        } finally {
            serverBPlaintext.shutdown()
        }
    }

    /**
     * HTTPS A → HTTPS B redirect must not follow; server B count = 0.
     * Uses actual TLS MockWebServer for A, redirect points to actual TLS server B.
     */
    @Test fun `https_A_redirect_https_B_no_follow_bcount0`() = runBlocking {
        // Server A (HTTPS with real cert) redirects to server B (HTTPS with real cert)
        serverA.enqueue(MockResponse()
            .setResponseCode(302)
            .addHeader("Location", serverB.url("/target").toString()))

        val conn = testClient!!.newCall(
            okhttp3.Request.Builder().url(serverA.url("/")).build()
        ).execute()

        assertEquals(302, conn.code)
        conn.close()

        // Server B must have received ZERO requests
        assertEquals("Server B must not receive any requests on HTTPS→HTTPS redirect", 0, serverB.requestCount)
    }

    /**
     * 307 redirect with POST body must not follow (body should not be sent to target).
     * HTTPS A → actual HTTPS B. Server B must receive 0 requests.
     */
    @Test fun `redirect_307_post_not_followed_bcount0`() = runBlocking {
        // Server A: 307 → B (actual HTTPS)
        serverA.enqueue(MockResponse().setResponseCode(307).addHeader("Location", serverB.url("/").toString()))
        // Server B: never called
        serverB.enqueue(MockResponse().setBody("B-RESPONSE"))

        val body = """{"message":"hello"}""".toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )
        val conn = testClient!!.newCall(
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
     * 308 redirect with POST body must not follow. HTTPS A → actual HTTPS B.
     */
    @Test fun `redirect_308_post_not_followed_bcount0`() = runBlocking {
        serverA.enqueue(MockResponse().setResponseCode(308).addHeader("Location", serverB.url("/").toString()))
        serverB.enqueue(MockResponse().setBody("B-RESPONSE"))

        val body = """{"rpc":"test"}""".toRequestBody(
            "application/json; charset=utf-8".toMediaType()
        )
        val conn = testClient!!.newCall(
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
     * HTTPS A → actual HTTPS B for SSE stream test.
     */
    @Test fun `redirect_307_sse_not_followed_bcount0`() = runBlocking {
        // Server A: 307 redirect (simulating a streaming endpoint)
        serverA.enqueue(MockResponse().setResponseCode(307).addHeader("Location", serverB.url("/stream").toString()))
        // Server B would return SSE content — but should never be called
        serverB.enqueue(MockResponse()
            .setBody("data: hello\ndata: world\n")
            .addHeader("Content-Type: text/event-stream"))

        val conn = testStreamingClient!!.newCall(
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
     * Direct HTTPS with actual TLS works fine (no redirect involved).
     * Uses the test client derived from Http.base.newBuilder().
     */
    @Test fun `direct_https_works_with_test_client`() = runBlocking {
        val port = serverA.port
        serverA.enqueue(MockResponse().setBody("HTTPS-OK"))

        val conn = testClient!!.newCall(
            okhttp3.Request.Builder().url("https://localhost:$port/").build()
        ).execute()
        assertEquals(200, conn.code)
        assertEquals("HTTPS-OK", conn.body?.string())
        conn.close()
    }

    /**
     * Http.base with actual TLS and approval works.
     * HTTPS is always allowed through the gate.
     */
    @Test fun `direct_https_approved_gate_works`() = runBlocking {
        val gate = Http.testingGate
        val store = gate.approvedOrigins as InMemoryApprovedOriginsStore
        store.clearAll()

        val port = serverA.port
        serverA.enqueue(MockResponse().setBody("HTTPS-APPROVED"))

        // HTTPS is always allowed, no approval needed
        val validated = gate.validate("https://localhost:$port")
        assertTrue("HTTPS should be validated", validated.isNotEmpty())

        // Direct call works using test client derived from Http.base
        val conn = testClient!!.newCall(
            okhttp3.Request.Builder().url("https://localhost:$port/").build()
        ).execute()
        assertEquals(200, conn.code)
        assertEquals("HTTPS-APPROVED", conn.body?.string())
        conn.close()
    }

    /**
     * Approved HTTP A → 302 redirect to actual HTTP B: B must receive 0 requests.
     * Uses actual HTTP MockWebServer B (not other.invalid).
     * Derives client from Http.base.newBuilder() to preserve production flags.
     */
    @Test fun `approved_http_A_redirect_302_to_http_B_bcount0`() = runBlocking {
        val gate = Http.testingGate
        val store = gate.approvedOrigins as InMemoryApprovedOriginsStore
        store.clearAll()

        // Start dedicated plaintext HTTP servers (separate from TLS servers)
        val serverHttpA = MockWebServer().also { it.start(0) }
        val serverHttpB = MockWebServer().also { it.start(0) }

        try {
            val portA = serverHttpA.port
            val portB = serverHttpB.port
            serverHttpB.enqueue(MockResponse().setBody("B-RESPONSE"))

            // Approve the HTTP origin using canonicalEndpointIdentity
            store.addSync(canonicalEndpointIdentity("http://localhost:$portA"))

            // Server A (plaintext) redirects to actual HTTP server B
            serverHttpA.enqueue(MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "http://localhost:$portB/"))

            // Derive client from Http.base.newBuilder() — inherits followRedirects(false)
            val httpClient = Http.base.newBuilder().build()

            val conn = httpClient.newCall(
                okhttp3.Request.Builder().url("http://localhost:$portA/").build()
            ).execute()

            // Without redirect following, we get the 302 directly
            assertEquals(302, conn.code)
            conn.close()

            // Server B must have received ZERO requests
            assertEquals("Server B must not receive any requests on HTTP→HTTP redirect", 0, serverHttpB.requestCount)
        } finally {
            serverHttpA.shutdown()
            serverHttpB.shutdown()
        }
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

    /**
     * Verify test client derived from Http.base inherits no-redirect flags.
     */
    @Test fun `test_client_inherits_no_redirects_from_base`() {
        assertFalse("testClient must not follow redirects", testClient!!.followRedirects)
        assertFalse("testClient must not follow SSL redirects", testClient!!.followSslRedirects)
    }

    /**
     * Verify streaming test client inherits no-redirect flags.
     */
    @Test fun `test_streaming_client_inherits_no_redirects_from_base`() {
        assertFalse("testStreamingClient must not follow redirects", testStreamingClient!!.followRedirects)
        assertFalse("testStreamingClient must not follow SSL redirects", testStreamingClient!!.followSslRedirects)
    }
}