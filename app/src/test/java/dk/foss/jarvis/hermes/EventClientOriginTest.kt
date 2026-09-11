package dk.foss.jarvis.hermes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for originIdentity (canonical connection identity) and
 * legacyOriginIdentity (migration helper).
 *
 * The new originIdentity includes the full URL path and a non-reversible
 * hash of the API key, so that different profiles or keys on the same host
 * produce distinct identities.
 */
class OriginIdentityTest {

    // --- originIdentity: path differentiation ---

    @Test
    fun `origin identity distinguishes different paths`() {
        val a = originIdentity("https://example.com/p/perfilA", "key1")
        val b = originIdentity("https://example.com/p/perfilB", "key1")
        assertFalse(a == b)
        // Both should contain the path
        assertTrue(a.contains("/p/perfilA"))
        assertTrue(b.contains("/p/perfilB"))
    }

    @Test
    fun `origin identity same path same key yields same identity`() {
        val a = originIdentity("https://example.com/p/same", "key1")
        val b = originIdentity("https://example.com/p/same", "key1")
        assertEquals(a, b)
    }

    @Test
    fun `origin identity different key same url yields different identity`() {
        val a = originIdentity("https://example.com/p/same", "keyA")
        val b = originIdentity("https://example.com/p/same", "keyB")
        assertFalse(a == b)
    }

    // --- originIdentity: normalization ---

    @Test
    fun `origin identity folds default port https`() {
        val a = originIdentity("https://example.com:443/api")
        val b = originIdentity("https://example.com/api")
        assertEquals(a, b)
    }

    @Test
    fun `origin identity folds default port http`() {
        val a = originIdentity("http://example.com:80/api")
        val b = originIdentity("http://example.com/api")
        assertEquals(a, b)
    }

    @Test
    fun `origin identity lowercases scheme and host`() {
        val a = originIdentity("HTTPS://EXAMPLE.COM/api")
        val b = originIdentity("https://example.com/api")
        assertEquals(a, b)
    }

    @Test
    fun `origin identity strips trailing slash`() {
        val a = originIdentity("https://example.com/api/")
        val b = originIdentity("https://example.com/api")
        assertEquals(a, b)
    }

    @Test
    fun `origin identity preserves non-default port`() {
        val a = originIdentity("https://example.com:8443/api")
        val b = originIdentity("https://example.com:8443/api")
        assertEquals(a, b)
        val c = originIdentity("https://example.com/api")
        assertFalse(a == c)
    }

    // --- originIdentity: null/blank API key ---

    @Test
    fun `origin identity with null apiKey yields url only`() {
        val a = originIdentity("https://example.com/api", null)
        val b = originIdentity("https://example.com/api")
        assertEquals(a, b)
        // No hash suffix — just the URL
        assertFalse(a.contains("-"))
    }

    @Test
    fun `origin identity with blank apiKey yields url only`() {
        val a = originIdentity("https://example.com/api", "   ")
        val b = originIdentity("https://example.com/api", null)
        assertEquals(a, b)
    }

    @Test
    fun `origin identity null apiKey same url same key differs from with key`() {
        val noKey = originIdentity("https://example.com/api", null)
        val withKey = originIdentity("https://example.com/api", "secret")
        assertFalse(noKey == withKey)
    }

    // --- originIdentity: hash determinism ---

    @Test
    fun `origin identity hash is deterministic`() {
        repeat(3) {
            val a = originIdentity("https://x.com/p/a", "key123")
            val b = originIdentity("https://x.com/p/a", "key123")
            assertEquals(a, b)
        }
    }

    // --- legacyOriginIdentity ---

    @Test
    fun `legacy origin identity ignores trailing slash and default port`() {
        assertEquals(
            legacyOriginIdentity("HTTPS://Hermes.local/"),
            legacyOriginIdentity("https://hermes.local:443/api"),
        )
        // Wait, legacy does NOT include path, so /api should be ignored
        assertEquals(
            legacyOriginIdentity("https://hermes.local/api"),
            legacyOriginIdentity("https://hermes.local"),
        )
    }

    @Test
    fun `legacy origin identity distinguishes host and port`() {
        assertFalse(
            legacyOriginIdentity("http://hermes-a:8642") ==
                legacyOriginIdentity("http://hermes-b:8642"),
        )
        assertFalse(
            legacyOriginIdentity("http://hermes-a:8642") ==
                legacyOriginIdentity("http://hermes-a:8643"),
        )
    }

    @Test
    fun `legacy and new identity are different when path or key is present`() {
        val legacy = legacyOriginIdentity("https://example.com/p/a")
        val newWithKey = originIdentity("https://example.com/p/a", "key1")
        val newNoKey = originIdentity("https://example.com/p/a", null)

        // Legacy ignores path
        assertTrue(legacy.startsWith("https://example.com:443"))
        // New includes path
        assertFalse(newWithKey.startsWith("https://example.com:443"))
        assertTrue(newWithKey.contains("/p/a"))
    }
}

/**
 * Tests that EventClient.originIdentity (legacy delegate) still works
 * for callers in ConnectionTransition that use EventClient.originIdentity.
 */
class EventClientOriginIdentityTest {

    @Test
    fun `EventClient origin identity delegates to ChatTransport`() {
        val a = EventClient.originIdentity("https://example.com:443/api")
        val b = EventClient.originIdentity("https://EXAMPLE.COM/api/")
        assertEquals(a, b)
    }
}