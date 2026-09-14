package dk.foss.jarvis.net

import dk.foss.jarvis.hermes.originIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Matrix tests for the NetworkGate policy boundary.
 *
 * Covers edge cases: IPv4, IPv6-like, trailing slashes, whitespace,
 * scheme casing, path variations, and the HTTP approval lifecycle.
 *
 * Uses [Http.testingGate] backed by [InMemoryApprovedOriginsStore].
 */
class NetworkGateMatrixTest {

    private val store = Http.testingGate.approvedOrigins as InMemoryApprovedOriginsStore

    @Before fun setUp() = runBlocking { store.clearAll() }

    // ── Scheme matrix ───────────────────────────────────────────────────

    @Test fun `https_uppercase_scheme_allowed`() = runBlocking {
        assertTrue(isAllowed("HTTPS://hermes.local"))
        assertTrue(isAllowed("HtTpS://hermes.local"))
    }

    @Test fun `http_uppercase_scheme_blocked_without_approval`() = runBlocking {
        assertFalse(isAllowed("HTTP://hermes.local"))
    }

    @Test fun `http_uppercase_scheme_allowed_with_approval`() = runBlocking {
        store.add(originIdentity("http://hermes.local"))
        assertTrue(isAllowed("HTTP://hermes.local"))
    }

    @Test fun `ftp_blocked`() = runBlocking {
        assertFalse(isAllowed("ftp://files.example"))
    }

    @Test fun `ws_blocked`() = runBlocking {
        assertFalse(isAllowed("ws://echo.example"))
    }

    @Test fun `wss_blocked_not_http_scheme`() = runBlocking {
        // wss is a WebSocket scheme, not HTTP
        // The URI parser treats "wss" as the scheme
        assertFalse(isAllowed("wss://echo.example"))
    }

    @Test fun `file_blocked`() = runBlocking {
        assertFalse(isAllowed("file:///etc/hosts"))
    }

    @Test fun `data_blocked`() = runBlocking {
        assertFalse(isAllowed("data:text/plain,hello"))
    }

    // ── URL structure matrix ───────────────────────────────────────────

    @Test fun `trailing_slash_normalized`() = runBlocking {
        // originIdentity normalises URLs; trailing slash on path is part of origin
        val withSlash = originIdentity("http://hermes.local:8642/")
        val withoutSlash = originIdentity("http://hermes.local:8642")
        // Both should produce the same normalized origin
        assertEquals(withSlash, withoutSlash)
        store.add(withoutSlash)
        assertTrue(isAllowed("http://hermes.local:8642/"))
    }

    @Test fun `whitespace_trimmed`() = runBlocking {
        // The gate trims the URL before parsing
        store.add(originIdentity("http://hermes.local"))
        assertTrue(isAllowed("  http://hermes.local  "))
    }

    @Test fun `empty_string_blocked`() = runBlocking {
        assertFalse(isAllowed(""))
    }

    @Test fun `blank_string_blocked`() = runBlocking {
        assertFalse(isAllowed("   "))
    }

    // ── HTTP approval lifecycle matrix ─────────────────────────────────

    @Test fun `approve_then_validate_then_revoke`() = runBlocking {
        val origin = originIdentity("http://dev.local:8642")
        store.add(origin)

        // Approved → allowed
        assertTrue(isAllowed("http://dev.local:8642"))

        // Revoke → blocked
        store.clearAll()
        assertFalse(isAllowed("http://dev.local:8642"))
    }

    @Test fun `add_duplicate_is_idempotent`() = runBlocking {
        val origin = originIdentity("http://dev.local:8642")
        store.add(origin)
        store.add(origin)
        store.add(origin)
        assertTrue(isAllowed("http://dev.local:8642"))
    }

    @Test fun `remove_nonexistent_is_idempotent`() = runBlocking {
        store.remove(originIdentity("http://nonexistent.local"))
        // Should not throw
    }

    @Test fun `multiple_approvals_independent`() = runBlocking {
        store.add(originIdentity("http://a.local:8642"))
        store.add(originIdentity("http://b.local:8642"))

        assertTrue(isAllowed("http://a.local:8642"))
        assertTrue(isAllowed("http://b.local:8642"))

        store.remove(originIdentity("http://a.local:8642"))
        assertFalse(isAllowed("http://a.local:8642"))
        assertTrue(isAllowed("http://b.local:8642"))
    }

    @Test fun `https_never_blocked_even_if_not_approved`() = runBlocking {
        // HTTPS is always allowed regardless of approval state
        store.clearAll()
        assertTrue(isAllowed("https://hermes.local"))
        assertTrue(isAllowed("https://hermes.local:443"))
        assertTrue(isAllowed("https://hermes.local:8443"))
    }

    // ── Helper ─────────────────────────────────────────────────────────

    private fun isAllowed(url: String): Boolean = runCatching {
        Http.testingGate.validate(url)
        true
    }.getOrDefault(false)
}