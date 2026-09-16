package dk.foss.jarvis.hermes

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for canonicalEndpointIdentity — a URL-only canonical form used for
 * Settings / gate / UI comparisons.  Never includes an API-key fingerprint.
 *
 * Strict: throws IllegalArgumentException for invalid URIs — no fallback.
 */
class CanonicalEndpointIdentityTest {

    // ─── valid HTTP(S) URLs ───────────────────────────────────────────────

    @Test fun `http_default_port_folded`() {
        assertEquals("http://hermes.local/", canonicalEndpointIdentity("http://hermes.local"))
        assertEquals("http://hermes.local/", canonicalEndpointIdentity("http://hermes.local:80"))
    }

    @Test fun `https_default_port_folded`() {
        assertEquals("https://hermes.local/", canonicalEndpointIdentity("https://hermes.local"))
        assertEquals("https://hermes.local/", canonicalEndpointIdentity("https://hermes.local:443"))
    }

    @Test fun `non_default_port_preserved`() {
        assertEquals("http://hermes.local:8642/", canonicalEndpointIdentity("http://hermes.local:8642"))
        assertEquals("https://hermes.local:8642/", canonicalEndpointIdentity("https://hermes.local:8642"))
    }

    @Test fun `path_preserved`() {
        assertEquals("http://hermes.local/api/v1", canonicalEndpointIdentity("http://hermes.local/api/v1"))
        assertEquals("https://hermes.local/api/chat", canonicalEndpointIdentity("https://hermes.local/api/chat"))
    }

    @Test fun `trailing_slash_stripped`() {
        assertEquals("http://hermes.local/api", canonicalEndpointIdentity("http://hermes.local/api/"))
        assertEquals("https://hermes.local/api", canonicalEndpointIdentity("https://hermes.local/api/"))
    }

    @Test fun `host_lowercased`() {
        assertEquals("http://hermes.local/api", canonicalEndpointIdentity("http://HERMES.LOCAL/api"))
        assertEquals("https://hermes.local/api", canonicalEndpointIdentity("https://HerMES.Local/api"))
    }

    @Test fun `scheme_lowercased`() {
        assertEquals("http://hermes.local/", canonicalEndpointIdentity("HTTP://hermes.local"))
        assertEquals("https://hermes.local/", canonicalEndpointIdentity("HTTPS://hermes.local"))
    }

    @Test fun `whitespace_trimmed`() {
        assertEquals("http://hermes.local/api", canonicalEndpointIdentity("  http://hermes.local/api  "))
    }

    @Test fun `canonical_equivalent_forms_equal`() {
        val id1 = canonicalEndpointIdentity("http://HerMES.local:80/api/")
        val id2 = canonicalEndpointIdentity("http://hermes.local/api")
        val id3 = canonicalEndpointIdentity("http://hermes.local:80/api/")
        assertEquals(id1, id2)
        assertEquals(id2, id3)
    }

    @Test fun `http_vs_https_different`() {
        assertNotEquals(
            canonicalEndpointIdentity("http://hermes.local/api"),
            canonicalEndpointIdentity("https://hermes.local/api")
        )
    }

    @Test fun `different_host_different_identity`() {
        assertNotEquals(
            canonicalEndpointIdentity("http://hermes-a.local/api"),
            canonicalEndpointIdentity("http://hermes-b.local/api")
        )
    }

    @Test fun `different_path_different_identity`() {
        assertNotEquals(
            canonicalEndpointIdentity("http://hermes.local/v1"),
            canonicalEndpointIdentity("http://hermes.local/v2")
        )
    }

    @Test fun `different_port_different_identity`() {
        assertNotEquals(
            canonicalEndpointIdentity("http://hermes.local:8080"),
            canonicalEndpointIdentity("http://hermes.local:9090")
        )
    }

    // ─── strict: malformed input throws ──────────────────────────────────

    @Test fun `empty_url_throws`() {
        assertThrows(IllegalArgumentException::class.java) { canonicalEndpointIdentity("") }
    }

    @Test fun `whitespace_only_throws`() {
        assertThrows(IllegalArgumentException::class.java) { canonicalEndpointIdentity("   ") }
    }

    @Test fun `opaque_url_throws`() {
        // "mailto:user@example.com" is an opaque URI
        assertThrows(IllegalArgumentException::class.java) { canonicalEndpointIdentity("mailto:user@example.com") }
    }

    @Test fun `unsupported_scheme_throws`() {
        assertThrows(IllegalArgumentException::class.java) { canonicalEndpointIdentity("ftp://hermes.local") }
        assertThrows(IllegalArgumentException::class.java) { canonicalEndpointIdentity("file:///etc/passwd") }
        assertThrows(IllegalArgumentException::class.java) { canonicalEndpointIdentity("ws://hermes.local") }
    }

    @Test fun `missing_host_throws`() {
        // "http:" has a scheme but no host
        assertThrows(IllegalArgumentException::class.java) { canonicalEndpointIdentity("http:") }
    }

    @Test fun `userinfo_throws`() {
        assertThrows(IllegalArgumentException::class.java) { canonicalEndpointIdentity("http://user:pass@hermes.local") }
    }

    @Test fun `query_throws`() {
        assertThrows(IllegalArgumentException::class.java) { canonicalEndpointIdentity("http://hermes.local?foo=bar") }
    }

    @Test fun `fragment_throws`() {
        assertThrows(IllegalArgumentException::class.java) { canonicalEndpointIdentity("http://hermes.local#section") }
    }

    // ─── no API-key fingerprint ──────────────────────────────────────────

    @Test fun `no_hash_suffix`() {
        // The identity is URL-only — no dash+hash suffix
        val id = canonicalEndpointIdentity("https://hermes.local:8642/api")
        // Must NOT contain a 64-char hex digest after the last dash
        val parts = id.split('-')
        if (parts.size > 1) {
            val lastPart = parts.last()
            assertFalse("Should not end with 64 hex chars", lastPart.matches(Regex("[0-9a-f]{64}")))
        }
    }
}