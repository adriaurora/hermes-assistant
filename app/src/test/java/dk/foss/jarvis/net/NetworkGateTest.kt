package dk.foss.jarvis.net

import dk.foss.jarvis.hermes.canonicalEndpointIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Focused tests for NetworkGate validation policy.
 *
 * Uses [Http.testingGate] backed by an in-memory store.
 * No Android runtime required.
 *
 * Each test class should call [resetTestingGate] before use to get a fresh
 * isolated gate.
 */
class NetworkGateTest {

    private val store = Http.testingGate.approvedOrigins as InMemoryApprovedOriginsStore

    @Before fun setUp() {
        runBlocking {
            store.clearAll()
            (Http.testingGate.cleanupStore as? InMemoryApprovedOriginsStore)?.clearAll()
        }
    }

    private fun assertBlocked(baseUrl: String, reasonContains: String = "") = runBlocking {
        val ex = org.junit.Assert.assertThrows(BlockedRequest::class.java) { Http.testingGate.validate(baseUrl) }
        if (reasonContains.isNotBlank()) {
            assertTrue(ex.message?.contains(reasonContains, ignoreCase = true) ?: false)
        }
    }

    private fun assertAllowed(baseUrl: String): String = runBlocking { Http.testingGate.validate(baseUrl) }

    private fun isAllowed(url: String): Boolean = runCatching {
        Http.testingGate.validate(url)
        true
    }.getOrDefault(false)

    private fun isAllowedForCleanup(url: String): Boolean = runCatching {
        Http.testingGate.validateForCleanup(url)
        true
    }.getOrDefault(false)

    /** Lazily access the cleanup store from the shared gate. */
    private val cleanupStore: InMemoryApprovedOriginsStore
        get() = Http.testingGate.cleanupStore as InMemoryApprovedOriginsStore

    // 1. HTTPS always allowed — various forms
    @Test fun `https_allowed_always`() = runBlocking {
        // HTTPS is always allowed regardless of origin form
        val r1 = assertAllowed("https://h:1")
        val r2 = assertAllowed("https://h:443")
        val r3 = assertAllowed("https://hermes.local/api/chat")
        // Both should succeed (return origin identity string)
        assertTrue(r1.isNotEmpty())
        assertTrue(r2.isNotEmpty())
        assertTrue(r3.isNotEmpty())
        store.add("https://hermes.local/api/chat") // HTTPS does not need approval
    }

    // 2. HTTP without approval → blocked
    @Test fun `http_without_approval_blocked()`() = runBlocking {
        assertBlocked("http://hermes.local", "blocked")
        assertBlocked("http://hermes.local:8642", "blocked")
    }

    // 3. HTTP with approval → allowed
    @Test fun `http_with_approval_allowed()`() = runBlocking {
        store.add(canonicalEndpointIdentity("http://hermes.local:8642"))
        assertEquals(canonicalEndpointIdentity("http://hermes.local:8642"), assertAllowed("http://hermes.local:8642"))
    }

    // 4. Host-only HTTP (no path)
    @Test fun `http_host_only()`() = runBlocking {
        store.add(canonicalEndpointIdentity("http://hermes.local"))
        assertEquals(canonicalEndpointIdentity("http://hermes.local"), assertAllowed("http://hermes.local"))
    }

    // 5. HTTP with port 80 default
    @Test fun `http_port_80_default()`() = runBlocking {
        store.add(canonicalEndpointIdentity("http://hermes.local:80"))
        assertEquals(canonicalEndpointIdentity("http://hermes.local:80"), assertAllowed("http://hermes.local:80"))
    }

    // 6. HTTPS not in approved set → still allowed
    @Test fun `https_not_in_approved_set_allowed()`() = runBlocking {
        store.add("http://hermes.local:8642")
        assertEquals(canonicalEndpointIdentity("https://hermes.local"), assertAllowed("https://hermes.local"))
    }

    // 7. HTTPS not in cleanup set → still allowed
    @Test fun `https_not_in_cleanup_set_allowed()`() = runBlocking {
        cleanupStore.add("http://hermes.local:8642")
        assertEquals(canonicalEndpointIdentity("https://hermes.local"), assertAllowed("https://hermes.local"))
    }

    // 8. Unsupported scheme → blocked
    @Test fun `unsupported_scheme_blocked()`() = runBlocking {
        assertBlocked("ftp://hermes.local", "Unsupported scheme")
    }

    // 9. Empty URL → blocked
    @Test fun `empty_url_blocked()`() = runBlocking {
        assertBlocked("", "Empty URL")
    }

    // 10. Malformed URL → blocked
    @Test fun `malformed_url_blocked()`() = runBlocking {
        assertBlocked("not a url", "Malformed")
    }

    // 11. HTTPS with path
    @Test fun `https_with_path()`() = runBlocking {
        assertEquals("https://hermes.local/api/v1", assertAllowed("https://hermes.local/api/v1"))
    }

    // 12. HTTP with path
    @Test fun `http_with_path()`() = runBlocking {
        store.add(canonicalEndpointIdentity("http://hermes.local:8642/api"))
        assertEquals(canonicalEndpointIdentity("http://hermes.local:8642/api"), assertAllowed("http://hermes.local:8642/api"))
    }

    // 13. HTTPS with default port folded
    @Test fun `https_default_port_folded()`() = runBlocking {
        val r = assertAllowed("https://hermes.local:443")
        assertEquals(canonicalEndpointIdentity("https://hermes.local:443"), r)
    }

    // 14. HTTP with default port folded
    @Test fun `http_default_port_folded()`() = runBlocking {
        store.add(canonicalEndpointIdentity("http://hermes.local"))
        val r = assertAllowed("http://hermes.local:80")
        assertEquals(canonicalEndpointIdentity("http://hermes.local"), r)
    }

    // 15. HTTPS never needs approval
    @Test fun `https_never_needs_approval()`() = runBlocking {
        assertEquals(canonicalEndpointIdentity("https://hermes.local"), assertAllowed("https://hermes.local"))
        store.clearAll()
        assertEquals(canonicalEndpointIdentity("https://hermes.local"), assertAllowed("https://hermes.local"))
    }

    // 16. HTTP needs explicit approval
    @Test fun `http_needs_explicit_approval()`() = runBlocking {
        store.add(canonicalEndpointIdentity("http://hermes.local"))
        assertEquals(canonicalEndpointIdentity("http://hermes.local"), assertAllowed("http://hermes.local"))
        // Other origin not added → blocked
        assertBlocked("http://other.local", "blocked")
    }

    // 17. HTTP with userinfo blocked
    @Test fun `http_with_userinfo_blocked()`() = runBlocking {
        store.add(canonicalEndpointIdentity("http://hermes.local"))
        // Even approved HTTP with userinfo is blocked
        assertBlocked("http://user:pass@hermes.local", "userinfo")
    }

    @Test fun `http_with_query_blocked`() = runBlocking {
        store.add(canonicalEndpointIdentity("http://hermes.local"))
        assertBlocked("http://hermes.local?debug=true", "query")
    }

    @Test fun `http_with_fragment_blocked`() = runBlocking {
        store.add(canonicalEndpointIdentity("http://hermes.local"))
        assertBlocked("http://hermes.local#section", "fragment")
    }

    @Test fun `opaque_uri_blocked_http`() = runBlocking {
        assertBlocked("http:hermes.local", "opaque")
    }

    @Test fun `opaque_uri_blocked_https`() = runBlocking {
        assertBlocked("https:hermes.local", "opaque")
    }

    // ── Scope separation tests (active vs cleanup) ────────────────

    /**
     * After moveForCleanup, ordinary validation blocks but cleanup validation
     * allows (because the origin is in the active set before move, and is
     * then checked via the cleanup allowance).
     *
     * Note: validateForCleanup allows origins in EITHER the active set OR
     * the cleanup allowance. After a move, the origin has been moved from
     * active to cleanup, so:
     * - validate() only checks active → BLOCKED
     * - validateForCleanup() checks active OR cleanup → ALLOWED (cleanup set)
     */
    @Test fun `move_for_cleanup_blocks_ordinary_allows_cleanup()`() = runBlocking {
        val origin = canonicalEndpointIdentity("http://hermes-a.local:8642")
        store.add(origin)

        // Before move: both allowed
        assertTrue("Before move: ordinary must be allowed", isAllowed("http://hermes-a.local:8642"))
        assertTrue("Before move: cleanup must be allowed", isAllowedForCleanup("http://hermes-a.local:8642"))

        // After move: ordinary blocked (not in active), cleanup allowed (in cleanup)
        store.moveForCleanup(origin)
        assertFalse("After move: ordinary must be BLOCKED", isAllowed("http://hermes-a.local:8642"))
        assertTrue("After move: cleanup must be ALLOWED", isAllowedForCleanup("http://hermes-a.local:8642"))

        // Cleanup only check: same result
        assertTrue("Cleanup check must also succeed", isAllowedForCleanup("http://hermes-a.local:8642"))
    }

    /**
     * Manual revoke removes from both scopes — no ordinary and no cleanup.
     */
    @Test fun `manual_revoke_clears_both_scopes()`() = runBlocking {
        val origin = canonicalEndpointIdentity("http://hermes-a.local:8642")
        store.add(origin)
        store.moveForCleanup(origin)

        // After move: ordinary blocked, cleanup allowed
        assertFalse(isAllowed("http://hermes-a.local:8642"))
        assertTrue(isAllowedForCleanup("http://hermes-a.local:8642"))

        // Manual revoke from cleanup
        cleanupStore.removeFromCleanup(origin)
        assertFalse("After cleanup revoke: ordinary must still be blocked", isAllowed("http://hermes-a.local:8642"))
        assertFalse("After cleanup revoke: cleanup must be blocked", isAllowedForCleanup("http://hermes-a.local:8642"))
    }

    /**
     * Clear all removes both active and cleanup.
     */
    @Test fun `clear_all_cleans_both_scopes()`() = runBlocking {
        val origin = canonicalEndpointIdentity("http://hermes-a.local:8642")
        store.add(origin)
        store.moveForCleanup(origin)

        assertTrue("Before clear: cleanup allowed", isAllowedForCleanup("http://hermes-a.local:8642"))
        store.clearAll()
        assertFalse("After clear: ordinary must be blocked", isAllowed("http://hermes-a.local:8642"))
        assertFalse("After clear: cleanup must be blocked", isAllowedForCleanup("http://hermes-a.local:8642"))
    }

    @Test fun `https_allowed_in_both_scopes()`() = runBlocking {
        assertTrue("HTTPS must be allowed for ordinary", isAllowed("https://hermes-a.local:8642"))
        assertTrue("HTTPS must be allowed for cleanup", isAllowedForCleanup("https://hermes-a.local:8642"))
    }
}