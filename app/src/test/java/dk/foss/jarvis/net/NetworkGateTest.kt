package dk.foss.jarvis.net

import dk.foss.jarvis.hermes.originIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused tests for NetworkGate validation policy.
 *
 * Uses the [Http.testingGate] which is backed by an in-memory store.
 * No Android runtime required.
 */
class NetworkGateTest {

    private val store = Http.testingGate.approvedOrigins as InMemoryApprovedOriginsStore

    @Before fun setUp() = runBlocking { store.clearAll() }

    private fun assertBlocked(baseUrl: String, reasonContains: String = "") = runBlocking {
        val ex = org.junit.Assert.assertThrows(BlockedRequest::class.java) { Http.testingGate.validate(baseUrl) }
        if (reasonContains.isNotBlank()) {
            assertTrue(ex.message?.contains(reasonContains, ignoreCase = true) ?: false)
        }
    }

    private fun assertAllowed(baseUrl: String): String = runBlocking { Http.testingGate.validate(baseUrl) }

    // 1. HTTPS always allowed — various forms
    @Test fun `https_allowed_always`() = runBlocking {
        // HTTPS is always allowed regardless of origin form
        val r1 = assertAllowed("https://h:1")
        val r2 = assertAllowed("https://h:1:443")
        // Both should succeed (return origin identity string)
        assertTrue(r1.isNotEmpty())
        assertTrue(r2.isNotEmpty())
        store.add("https://hermes.local/api/chat") // HTTPS does not need approval
    }

    // 2. HTTP without approval → blocked
    @Test fun `http_without_approval_blocked`() = runBlocking {
        assertBlocked("http://hermes.local", "Insecure HTTP")
    }

    // 3. HTTP with approval → allowed
    @Test fun `http_with_approval_allowed`() = runBlocking {
        val origin = originIdentity("http://hermes.local")
        store.add(origin)
        store.add(originIdentity("http://other:8642"))
        assertEquals(origin, assertAllowed("http://hermes.local"))
    }

    // 4. Malformed URL → blocked
    @Test fun `malformed_url_blocked`() = runBlocking {
        // "://broken" is parsed by URI.create which throws URISyntaxException
        // Our validate wraps it in runCatching and returns BlockedRequest
        val caught = runCatching { Http.testingGate.validate("://broken") }
        assertTrue(caught.isFailure)
        val ex = caught.exceptionOrNull()
        assertTrue(ex is BlockedRequest || ex?.message?.contains("Malformed", ignoreCase = true) == true)
    }

    // 5. Unsupported scheme → blocked
    @Test fun `unsupported_scheme_blocked`() = runBlocking {
        assertBlocked("file:///etc/hosts", "Unsupported scheme")
        assertBlocked("ftp://server", "Unsupported scheme")
        assertBlocked("socks5://proxy", "Unsupported scheme")
        assertBlocked("ws://echo", "Unsupported scheme")
    }

    // 6. Default port handling — HTTP default 80 should fold
    @Test fun `http_default_port_folds`() = runBlocking {
        val origin80 = originIdentity("http://hermes.local:80")
        val originPlain = originIdentity("http://hermes.local")
        assertEquals(origin80, originPlain)
        store.clearAll()
        store.add(originPlain)
        // After adding the plain origin, the explicit-80 version should also be approved
        assertEquals(originPlain, assertAllowed("http://hermes.local:80"))
    }

    // 7. Default port handling — HTTPS default 443 should fold
    @Test fun `https_default_port_folds`() = runBlocking {
        val origin443 = originIdentity("https://hermes.local:443")
        val originPlain = originIdentity("https://hermes.local")
        assertEquals(origin443, originPlain)
    }

    // 8. Non-default port — needs separate approval
    @Test fun `http_non_default_port_separate_approval`() = runBlocking {
        // Approve port 8642
        store.add(originIdentity("http://hermes.local:8642"))
        // Port 9000 is a different origin
        assertBlocked("http://hermes.local:9000", "Insecure HTTP")
    }

    // 9. Path is part of the origin
    @Test fun `path_is_part_of_origin`() = runBlocking {
        val originApi = originIdentity("http://hermes.local:8642/api")
        store.add(originApi)
        assertEquals(originApi, assertAllowed("http://hermes.local:8642/api"))
        // Different path → different origin → blocked
        assertBlocked("http://hermes.local:8642/v1", "Insecure HTTP")
    }

    // 10. Case normalisation — host is lowercased
    @Test fun `host_lowercased`() = runBlocking {
        val origin = originIdentity("http://hermes.local:8642")
        store.add(origin)
        assertEquals(origin, assertAllowed("http://HERMES.LOCAL:8642"))
    }
}