package dk.foss.jarvis.hermes

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that originIdentity is URL-only canonical identity (no API key).
 * API key rotation, case differences, default port normalization, and
 * trailing slash all produce the same identity.
 */
class OriginIdentityUrlOnlyTest {

    // 1. API key does NOT affect identity
    @Test fun `different_api_keys_same_identity`() {
        val id1 = originIdentity("https://hermes.local:8642/api", "key-a")
        val id2 = originIdentity("https://hermes.local:8642/api", "key-b")
        val id3 = originIdentity("https://hermes.local:8642/api", null)
        assertEquals(id1, id2)
        assertEquals(id2, id3)
    }

    @Test fun `blank_api_key_same_identity`() {
        val id1 = originIdentity("https://hermes.local/api", "key-a")
        val id2 = originIdentity("https://hermes.local/api", "")
        val id3 = originIdentity("https://hermes.local/api", null)
        assertEquals(id1, id2)
        assertEquals(id2, id3)
    }

    // 2. Default port folding
    @Test fun `default_http_port_folded`() {
        val id1 = originIdentity("http://hermes.local:80")
        val id2 = originIdentity("http://hermes.local")
        assertEquals(id1, id2)
        assertEquals("http://hermes.local/", id1)
    }

    @Test fun `default_https_port_folded`() {
        val id1 = originIdentity("https://hermes.local:443")
        val id2 = originIdentity("https://hermes.local")
        assertEquals(id1, id2)
        assertEquals("https://hermes.local/", id1)
    }

    // 3. Non-default port preserved
    @Test fun `non_default_port_preserved`() {
        val id1 = originIdentity("http://hermes.local:8642")
        val id2 = originIdentity("https://hermes.local:8642")
        assertEquals("http://hermes.local:8642/", id1)
        assertEquals("https://hermes.local:8642/", id2)
        // Different ports produce different identities
        val id3 = originIdentity("http://hermes.local:9999")
        assertEquals("http://hermes.local:9999/", id3)
    }

    // 4. Trailing slash normalization
    @Test fun `trailing_slash_normalized`() {
        val id1 = originIdentity("http://hermes.local/api/")
        val id2 = originIdentity("http://hermes.local/api")
        assertEquals(id1, id2)
    }

    // 5. Host lowercased
    @Test fun `host_lowercased`() {
        val id1 = originIdentity("http://HERMES.LOCAL/api")
        val id2 = originIdentity("http://hermes.local/api")
        assertEquals(id1, id2)
    }

    // 6. Path preserved
    @Test fun `path_preserved`() {
        val id1 = originIdentity("http://hermes.local/api/v1")
        val id2 = originIdentity("http://hermes.local/api/v2")
        assertEquals("http://hermes.local/api/v1", id1)
        assertEquals("http://hermes.local/api/v2", id2)
    }

    // 7. Scheme lowercased
    @Test fun `scheme_lowercased`() {
        val id1 = originIdentity("HTTP://hermes.local")
        val id2 = originIdentity("http://hermes.local")
        assertEquals(id1, id2)
    }

    // 8. Whitespace trimmed
    @Test fun `whitespace_trimmed`() {
        val id1 = originIdentity("  http://hermes.local/api  ")
        val id2 = originIdentity("http://hermes.local/api")
        assertEquals(id1, id2)
    }

    // 9. No API key hash — identity is purely URL
    @Test fun `identity_is_purely_url_no_hash`() {
        val id = originIdentity("https://h:8642/api", "secret-key")
        // Must not contain any SHA-256 hex digest (64 chars)
        assertEquals("https://h:8642/api", id)
    }

    // 10. HTTPS originIdentity with API key
    @Test fun `https_originIdentity_with_api_key_same_as_without`() {
        val withKey = originIdentity("https://hermes.local/v1", "k1")
        val withoutKey = originIdentity("https://hermes.local/v1", null)
        assertEquals(withKey, withoutKey)
        assertEquals("https://hermes.local/v1", withKey)
    }

    // 11. Host case + default port + trailing slash — all equivalent
    @Test fun `canonical_equivalent_variations`() {
        val id1 = originIdentity("http://HerMES.local:80/api/")
        val id2 = originIdentity("http://hermes.local/api")
        val id3 = originIdentity("http://hermes.local:80/api/")
        assertEquals(id1, id2)
        assertEquals(id2, id3)
    }

    // 12. Real host/port/path changes produce different identity
    @Test fun `real_host_change_different_identity`() {
        val id1 = originIdentity("http://hermes-a.local/api")
        val id2 = originIdentity("http://hermes-b.local/api")
        assertEquals("http://hermes-a.local/api", id1)
        assertEquals("http://hermes-b.local/api", id2)
        // Different hosts are different
        assertEquals(id1 != id2, true)
    }

    // 13. HTTP vs HTTPS different identity
    @Test fun `http_vs_https_different_identity`() {
        val id1 = originIdentity("http://hermes.local/api")
        val id2 = originIdentity("https://hermes.local/api")
        assertEquals("http://hermes.local/api", id1)
        assertEquals("https://hermes.local/api", id2)
    }
}