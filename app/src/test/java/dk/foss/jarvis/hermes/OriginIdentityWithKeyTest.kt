package dk.foss.jarvis.hermes

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that originIdentity includes a SHA-256 fingerprint of the API key
 * for conversation isolation: the same host with different keys produces
 * different identities, preserving required per-key conversation binding.
 *
 * When [apiKey] is null or blank the URL forms the identity alone.
 */
class OriginIdentityWithKeyTest {

    /** Same URL, different keys → different identities (full 64-hex SHA-256). */
    @Test fun `different_api_keys_different_identity`() {
        val id1 = originIdentity("https://hermes.local:8642/api", "key-a")
        val id2 = originIdentity("https://hermes.local:8642/api", "key-b")
        assertNotEquals(id1, id2)
        // Each must contain the canonical URL prefix
        assertTrue(id1.startsWith("https://hermes.local:8642/api-"))
        assertTrue(id2.startsWith("https://hermes.local:8642/api-"))
        // Each must end with a 64-char hex digest (SHA-256)
        assertTrue(id1.substringAfterLast('-').length == 64)
        assertTrue(id2.substringAfterLast('-').length == 64)
    }

    @Test fun `blank_api_key_produces_url_only`() {
        val withBlank = originIdentity("https://hermes.local/api", "")
        val withNull = originIdentity("https://hermes.local/api", null)
        assertEquals(withBlank, withNull)
        // No dash-hash suffix
        assertFalse(withBlank.contains("-"))
    }

    @Test fun `null_api_key_produces_url_only`() {
        val identity = originIdentity("https://hermes.local/v1", null)
        assertEquals("https://hermes.local/v1", identity)
    }

    /** Same URL, same key → same identity. */
    @Test fun `same_key_same_identity`() {
        val key = "hermes-bearer-token"
        val id1 = originIdentity("https://hermes.local/v1", key)
        val id2 = originIdentity("https://hermes.local/v1", key)
        assertEquals(id1, id2)
    }

    /** Default port folding works, and same canonical form with same key is equal. */
    @Test fun `default_port_folding_with_key`() {
        val key = "mykey"
        val id1 = originIdentity("http://hermes.local:80/api", key)
        val id2 = originIdentity("http://hermes.local/api", key)
        assertEquals(id1, id2)
        // Must end with a 64-char hex digest
        assertEquals(64, id1.substringAfterLast('-').length)
    }

    /** Non-default port preserved with key. */
    @Test fun `non_default_port_preserved_with_key`() {
        val id1 = originIdentity("http://hermes.local:8642/api", "k")
        val id2 = originIdentity("https://hermes.local:8642/api", "k")
        assertTrue(id1.startsWith("http://hermes.local:8642/api-"))
        assertTrue(id2.startsWith("https://hermes.local:8642/api-"))
        // Different schemes are different
        assertNotEquals(id1, id2)
    }

    /** Host lowercased before hashing. */
    @Test fun `host_lowercased_with_key`() {
        val id1 = originIdentity("http://HERMES.LOCAL/api", "k")
        val id2 = originIdentity("http://hermes.local/api", "k")
        assertEquals(id1, id2)
    }

    /** Path preserved with key. */
    @Test fun `path_preserved_with_key`() {
        val id1 = originIdentity("http://hermes.local/api/v1", "k")
        val id2 = originIdentity("http://hermes.local/api/v2", "k")
        assertNotEquals(id1, id2)
    }

    /** Trailing slash folded with key. */
    @Test fun `trailing_slash_normalized_with_key`() {
        val key = "k"
        val id1 = originIdentity("http://hermes.local/api/", key)
        val id2 = originIdentity("http://hermes.local/api", key)
        assertEquals(id1, id2)
    }

    /** Malformed URL → fallback to lowercase trimmed. */
    @Test fun `malformed_url_fallback`() {
        val result = originIdentity("not-a-url", "k")
        assertEquals("not-a-url", result.lowercase())
    }
}