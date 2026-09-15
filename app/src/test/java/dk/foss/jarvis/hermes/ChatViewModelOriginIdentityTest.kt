package dk.foss.jarvis.hermes

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests that originIdentity includes an API-key SHA-256 fingerprint for
 * conversation isolation.  Different keys on the same host produce different
 * identities, preserving required per-key conversation binding.
 *
 * This is the format stored in Conversation.origin and compared in
 * ChatTransportSelector and conversation origin identity handling.
 */
class ChatViewModelOriginIdentityTest {

    /** originIdentity with apiKey includes a 64-char hex SHA-256 suffix. */
    @Test
    fun `ChatViewModel_originIdentity_includes_api_key_hash`() {
        val baseUrl = "https://hermes.example.com/api/chat"
        val apiKey = "secret-api-key-123"
        val identity = originIdentity(baseUrl, apiKey)

        // Must include the path component
        assertTrue("Identity must include the URL path", identity.contains("/api/chat"))

        // Must include the key hash — dash + 64 hex chars
        val suffix = identity.substringAfterLast('-')
        assertEquals("Identity must end with a 64-char SHA-256 hex digest", 64, suffix.length)
        assertTrue("Suffix must be hex", suffix.all { it in '0'..'9' || it in 'a'..'f' })
    }

    /** Different apiKeys on the same URL produce DIFFERENT identities. */
    @Test
    fun `ConversationViewModel_originIdentity_different_keys_different_identity`() {
        val baseUrl = "https://hermes.local/v1"
        val identity = originIdentity(baseUrl, "hermes-bearer-token")
        val identity2 = originIdentity(baseUrl, "different-key")
        val identity3 = originIdentity(baseUrl, null)

        // Different keys → different identities
        assertNotEquals(identity, identity2)
        // Null key → URL only (no hash suffix)
        assertNotEquals(identity3, identity)
        // Null key → URL only (no hash suffix)
        assertNotEquals(identity3, identity2)

        // Null-key identity is URL-only
        assertEquals("https://hermes.local/v1", identity3)
    }

    /** originIdentity(baseUrl) without apiKey produces URL-only identity. */
    @Test
    fun `originIdentity_without_apiKey_no_hash_suffix`() {
        val identity = originIdentity("https://hermes.example.com/api/chat")
        // Must not contain a dash-hash suffix
        val hasDashAndHash = identity.contains('-') &&
            identity.substringAfterLast('-').matches(Regex("[0-9a-f]{64}"))
        assertTrue("Identity without key must NOT contain a dash-hash suffix", !hasDashAndHash)
    }

    /** Path differences produce different identities. */
    @Test
    fun `path_differences_produce_different_identity`() {
        val id1 = originIdentity("https://hermes.local/v1", "key-a")
        val id2 = originIdentity("https://hermes.local/v2", "key-b")
        // Both must have hash suffixes
        assertEquals(64, id1.substringAfterLast('-').length)
        assertEquals(64, id2.substringAfterLast('-').length)
        // Different paths are different
        assertNotEquals(id1, id2)
    }

    /** Same URL + same key → stable identity. */
    @Test
    fun `same_url_same_key_stable_identity`() {
        val baseUrl = "https://hermes.example.com/api/chat"
        val apiKey = "secret-api-key-123"
        val id1 = originIdentity(baseUrl, apiKey)
        val id2 = originIdentity(baseUrl, apiKey)
        assertEquals(id1, id2)
    }

    /** HTTPS originIdentity with API key includes hash. */
    @Test
    fun `https_originIdentity_includes_hash`() {
        val identity = originIdentity("https://hermes.local/v1", "k1")
        assertEquals(64, identity.substringAfterLast('-').length)
    }
}